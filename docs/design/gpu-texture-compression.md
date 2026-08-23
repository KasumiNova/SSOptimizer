# GPU 纹理压缩设计（BC 族压缩纹理接入）

状态：**正式设计，未实施**。前置条件已全部满足（字体重写收官）。平台范围：Linux / Windows，
不考虑 macOS。事实基线来自 2026-08 纹理管线调研（代码引用见各节）；
体积/质量/耗时数字来自 2026-08-22 离线实验室实测（`.dev/texcompress-lab/REPORT.md`，
269 张游戏+mod 真实贴图，bc7enc + rgbcx + bcdec roundtrip）。

## 1. 背景与动机

游戏贴图（舰船/武器图集、背景、UI）当前以未压缩 RGBA8 常驻显存：`uploadConverted`
恒以 `GL_RGBA + GL_UNSIGNED_BYTE` 上传（LazyTextureManager.java:717-746），
图集页 8192² 单页即 256MB 显存（ShipWeaponAtlas.composeAndUpload:324-348）。
接入 BC 族压缩纹理的收益：

- **显存 1/4~1/8**：BC7/BC3 = 8bpp（8192² → 64MB/页），BC1 = 4bpp。
  **实测（269 张真实贴图）：显存估算 137.1MB → 30.2MB（4.5×）；磁盘形态经 zstd 二压后
  仅为原始 RGBA 的 10.9%（BC7）/ 7.7%（BC3）/ 5.7%（BC1）**；
- **上传带宽同比下降**：RT 模式下像素在入队时快照拷贝（BufferSnapshotPoolImpl），
  压缩后快照体积同步缩小；
- **采样缓存友好**：压缩纹理解压在京厂 GPU 纹理单元内完成，显存占用与随机访问
  局部性均改善。

## 2. 原版/现状管线事实基线（已调研证实）

- **上传汇聚点两处**：PNG 纹理（原版+mod 同路，TextureLoader.loadTexture 唯一入口）
  → `LazyTextureManager.uploadConverted`；图集页 → `ShipWeaponAtlas.composeAndUpload`。
  旁路：程序化贴图 `loadTexture(String,BufferedImage)`、FontAtlasGl（ALPHA8 单通道，
  不宜 BC）、RadarCompositeCache（生成型纹理）。
- **internal format 恒 6408**，无 `glCompressedTexImage2D` 调用痕迹；mipmap 走
  `GL_GENERATE_MIPMAP`（≤1024² 或 specialMipmapSet 命中时，LazyTextureManager.java:748-767；
  图集页强制生成，ShipWeaponAtlas.java:344）。
- **NPOT 常态存在**：`TextureDimensionSupport` 在 GL20/ARB_NPOT 下保留原尺寸
  （现代 GPU 全命中），BC 4×4 块对齐需在编码器输入侧 pad。
- **GL 能力探测几乎为零**：全库仅 TextureDimensionSupport 查过一次 ContextCapabilities，
  S3TC/BPTC 探测需新建。
- **缓存体系可照搬**：TextureConversionCache（`mods/ssoptimizer/cache/textures/zstd/v4/`——
  v3 → v4：头部追加实际像素 alpha 内容 alphaKind，见 §3.1；
  源字节 SHA-256 键 + path|mtime|size 指纹 + 内存 LRU + 后台预热）与 FontPackCache
  「引擎形态入指纹」模式。
- **上下文重建路径硬编码 6408**：`reloadTextureInPlace`（LazyTextureManager.java:1327）
  反射调原版 in-place load，压缩后必须同步改造，否则重建前后形态不一致。
- **诊断已有估算基线**：`estimatedGpuBytes`（4B/px + mip 链）+ TSV 组成报表 + bind 统计，
  可直接做压缩前后对照；无真实显存查询（GL_NVX_gpu_memory_info 可作增强，非必须）。
- **native 侧 glad 已加载 `glCompressedTexImage2D` 指针**（glad.cpp:4850），native 模块
  新增仅需 settings.gradle.kts + 子目录 build.gradle.kts + Java 桥接模块
  headerOutputDirectory 三处。

## 3. 方案选型

### 3.1 压缩格式

| 格式 | bpp | alpha | GL 要求 | 角色 |
|---|---|---|---|---|
| **BC7**（BPTC_UNORM） | 8 | 完整高质量 | GL 4.2 / `ARB_texture_compression_bptc` | **首选：bptc 可用时一律 BC7**（质量优先，含实际不透明贴图） |
| **BC3**（S3TC DXT5） | 8 | 插值 alpha（质量中） | `EXT_texture_compression_s3tc`（近全平台） | BC7 不可用时的回退档（完整 alpha） |
| **BC1**（S3TC DXT1） | 4 | 1bit/无 | 同上 | **收窄为两条路径**：S3TC 回退下按实际 alpha 内容分流（不透明/二值 alpha 大图）；或 `bc1ForOpaque=true` 时的实际不透明大图 |

决策：**质量优先——bptc 可用时一律 BC7**；BC1 仅在以下情形使用：
1. S3TC 回退路径（bptc 不可用或强制 `format=bc3`）：按**实际像素 alpha 内容**
   （AlphaKind，见下）选择——全不透明 → BC1；alpha 仅 0/255 → BC1（1-bit
   punch-through alpha，rgbcx 不支持透明色板，该路径由 texcompress_core 自实现
   3-color 块编码）；其余 → BC3；
2. 用户显式开启 `ssoptimizer.texcompress.bc1ForOpaque=true`（默认 false）：
   bptc 可用时实际全不透明的大图（max(w,h) ≥ 256）也用 BC1，显存再减半，
   代价是平滑渐变可能出现 4bpp 块效应色带。

三者皆不可用 → 不压缩走现状路径（记一次 info 日志）。不引入 BC6H（游戏无 HDR 纹理源）。

**AlphaKind 实际像素检测**（2026-08-22 实机暴露问题驱动）：`ColorModel.hasAlpha()`
只说明图片「声明了 alpha 通道」，大量贴图声明 RGBA 但 alpha 实际全 255——
旧逻辑按声明选格式导致这类贴图落 BC7 浪费一半显存、而大平滑不透明贴图反落 BC1
出现色带。现由 `TexturePixelConverter.convert` 的既有像素遍历捎带统计
OPAQUE（全 255）/ BINARY（仅 0/255）/ FULL（含中间值），随转换结果落盘进
ssotex 元数据（schema v3 → **v4**，头部 `hasAlpha` 后追加 1 字节 alphaKind 序数），
格式选择直接读元数据，无需二次扫描。

实测修正（2026-08-22 离线实验室 + 实机验证）：
- **BC1 不能按「声明无 alpha」启用**：实机暴露两类误配——声明 RGBA 但 alpha 实际
  全 255 的贴图按旧逻辑落 BC7（浪费一半显存）；大而平滑的 opaque 贴图（星云背景等）
  落 BC1 出现 4bpp 块效应色带（不可接受）。现改按实际像素 alpha 内容（AlphaKind）
  且 bptc 可用时一律 BC7，BC1 收窄为 §3.1 的两条路径。BC1 尺寸下限
  max(w,h) ≥ 256 保留（无 alpha 小图标 BC1 低至 22.3dB，2048² 大图 46.1dB）。
  格式选择逻辑变更伴随 `CompressedTextureCache.ENCODER_VERSION` 1 → 2
  （旧 BC1 缓存自然失效）。
- **独立 bc7enc 的 alpha 通道偏弱**（modes 1/5/6/7，硬边 sprite alpha 不如 BC3 插值 alpha：
  实测 BC7 alpha 中位 37.4dB vs BC3 42.5dB）。若上线后 alpha 质量投诉集中，评估换
  bc7enc_rdo 内的 bc7e.ispc（全模式，需 ispc 构建链）或提高 uber_level。
- **小精灵是结构性质量洼地**：missiles/icons/weapons hardpoint（<100px）PSNR 系统性
  22~34dB（4×4 块粒度误差），且单张体积仅几 KB、收益绝对值小——**尺寸下限阈值跳过压缩**
  （初版：max(w,h) < 64 或原始 RGBA < 16KB 不压缩）。

### 3.2 编码器

| 候选 | 说明 | 评估 |
|---|---|---|
| **bc7enc / bcenc 族单文件库**（bc7enc_rdo 含 bc7e.ispc 与 rgbcx，Apache 2.0） | 数千行 C++ 单文件，直接内嵌 native 模块；bc7e 自称同质量比 ispc_texcomp 快 2–8x，rgbcx 为业界最高质量 BC1 编码器之一 | **首选**：构建链零增量（sso-native-module 插件已够用），许可干净可进 CI 复现，质量/速度档位齐全 |
| NVTT 3 | NVIDIA 官方，BC1–BC7/BC6H/ASTC 全族，C99/C++ API | **否决**（2026-08 查证）：闭源二进制 SDK，下载需 NVIDIA 开发者账号过登录墙，专有 EULA；无 macOS、无 vcpkg/Conan 配方（vcpkg 仅老版 2.1.2），CI 无法复现构建；差异化价值是 GPU 加速，但 BC7 最高质量档（Production/Highest）恰为 CPU-only，与本项目「离线后台预压」场景的匹配度并不优于开源方案 |
| 自研编码器 | — | 不采用：BC7 模式搜索空间工程量大，无差异化价值 |

### 3.3 压缩时机：**预压 + 缓存，运行时零压缩**

- BC7 编码 8192² 图集页需秒级~十秒级，运行时/首帧路径不可接受
  （实测：独立 bc7enc normal 档 16.6 MB/s 单线程，推算 8192² 页 ≈ 15s/页；
  2048² 单图 1.35s）；
- 策略：**缓存未命中时本轮以未压缩路径上传（行为=现状），后台线程压缩并写磁盘缓存；
  下次启动（或同会话重载）命中缓存走压缩上传**——「首次未压缩、二次压缩」，
  无任何帧时间风险，实现简单；
- 后台压缩走独立低优先级线程池（与 TexturePreparationRegistry worker 体系并列，
  不复用——避免压缩长任务挤占加载预备）。首轮全量压缩估算 20~40 分钟（单线程，
  对照现有 28753 文件缓存），多线程线性缩短，后台进行不影响游玩；
- **会话内热重传（T2 落地，替代原「初版不做」决策）**：后台压缩完成后，按
  resourcePath+源哈希匹配给驻留的未压缩受管纹理打升级键**并入待升级队列**
  （`LazyTextureManager.noteCompressedTextureAvailable`）；绑定路径（每帧必经的
  `bindTexture` 尾部）按节流（~5ms 间隔、单次 8 张预算）主动 drain 队列，在持
  GL 上下文线程上以同一 textureId 用 `glCompressedTexImage2D` 重指定存储
  （`maybeUpgradeToCompressed`，复用延迟上传同款的上下文检查与绑定捕获/恢复）。
  **主动 drain 不依赖该纹理再次被绑定**——短期渲染后不再绑定的纹理也会在驻留
  期间被升级，避免未压缩形态常驻显存；条目在 drain 前被驱逐则清标记跳过
  （重载时自然命中压缩缓存）。**drain 执行过升级后必须显式恢复调用方期望的绑定**
  （RT 模式逐次 capture/restore 是空转，不恢复会把后续绘制留在升级纹理上——
  串贴图事故根因）。内容逐像素一致、视觉无缝；键过期/缓存失效则清除
  标记不再重试。开关：`ssoptimizer.texcompress.hotreload=false` 关闭；
- **deferred prepass「仅压缩」预压**：对已进入 LazyTextureManager 索引但尚未加载的
  贴图（deferred-awaiting-first-bind，TSV 报告显示占大头），在两个 pending 登记点
  （`loadTexture` / `loadPreparedTexture`）按登记元数据（尺寸 + alphaKind + mip 标志）
  确定性格式选择后，向 TextureCompressionScheduler 投递 prepass 任务——worker 侧
  解码→转换（顺带写 ssotex 缓存与 alphaKind 元数据）→压缩→落盘缓存，
  **不做 GL 上传**，使首次 bind 直接命中压缩缓存，首轮会话即享受压缩形态。
  与像素任务共用同一低优先级单线程队列与同键去重；任务间 25ms 让步间隔，
  不抢加载关键路径。开关：`ssoptimizer.texcompress.deferredPrepass=false` 关闭；
- **eager 同步压缩模式（可选）**：`ssoptimizer.texcompress.mode=eager` 时缓存未
  命中即在加载线程同步压缩落盘、首轮直接压缩上传。首轮加载耗时显著增加
  （BC7 normal ≈ 16.6MB/s 单线程），建议搭配 `texcompress.quality=fast`；
  默认 background 保持「首轮零帧时间风险」。

### 3.4 压缩适用范围（实测修正后的排除面）

以下纹理**不压缩**，全程现状路径：
- 尺寸低于下限：max(w,h) < 64 或原始 RGBA < 16KB（小精灵质量洼地，见 §3.1）；
- 材质/法线贴图：文件名后缀 `_normal` / `_surface` / `_material`
  （离线样本中已发现混入案例；法线应走 BC5 或不压缩，初版不引入 BC5）；
- 字体图集（FontAtlasGl 单通道 ALPHA8）、程序化贴图、RadarCompositeCache 生成型纹理；
- 用户配置排除：`ssoptimizer.texcompress.excludePaths` 命中的路径（逗号分隔子串，
  大小写不敏感）完全不压缩、保持 RGBA8 上传。**默认排除面** =
  `background,starscape,nebula,illustration,/fx/`：背景/插画/星云/特效类大面积
  平滑渐变贴图实测在 high 档 BC7 下仍有可见色阶，画质优先直接排除（置空字符串
  恢复全量压缩）。
- BC1 额外限制：仅 max(w,h) ≥ 256，且实际像素 alpha 内容为 OPAQUE 或 BINARY
  （BINARY 走 1-bit punch-through alpha；FULL 永不 BC1）。bptc 可用时还需
  `bc1ForOpaque=true` 且 OPAQUE（质量优先默认全 BC7）。

## 4. 接入设计

### 4.1 上传路径改造

`LazyTextureManager.uploadConverted`（LazyTextureManager.java:717-746）在
`TexturePixelConverter.convert` 之后分流：

```
压缩可用(GL 探测通过) 且命中压缩缓存
  → glCompressedTexImage2D 逐级上传（含预生成 mip 链），textureId 形态=压缩
未命中
  → 现状 GL_RGBA 上传 + 投递后台压缩任务（源像素已在内存，直接复用）
```

- **mipmap 必须预生成**：压缩格式不可依赖 `GL_GENERATE_MIPMAP`（多数驱动对压缩
  格式不生成）。编码器输入侧逐级下采样（box/lanczos）→ 逐级压缩 → 容器落盘；
  上传时按级别 `glCompressedTexImage2D`。**仅对现状会生成 mip 的纹理类别生成链**
  （判定沿用 `shouldGenerateMipmaps`），无 mip 纹理只压 base 级。
- **4×4 对齐**：编码器输入 pad 到 4 的倍数（边缘复制，与图集 16px padding 防渗色
  同策略）；GL 侧允许非 4 倍数尺寸的压缩纹理（边缘块裁剪），texImage 尺寸仍传原尺寸。
- RT 模式兼容：压缩数据入队快照体积更小，路径不变（bridge GL11 需补
  `glCompressedTexImage2D` 转发——当前 bridge 未覆盖此方法，属新增桥接面）。

### 4.2 图集页压缩

`ShipWeaponAtlas.composeAndUpload` 同样分流。图集是收益主体（单页 256MB→64MB）：
- 页在 CPU 合成后即得完整像素，压缩输入现成；
- 图集缓存键需含「页组成指纹」（参与贴图清单 hash）——AtlasPacker 输出确定性
  则页内容确定性，可直接对合成后页像素 SHA-256；
- 16px padding 已满足块边界防渗色；mip 链逐级压缩时需验证跨 region 渗色
  （padding 16px 在深 mip 层不够——图集页 8192² 仅生成有限层级或提高 padding，
  实现时以 dumpdir 导出验证）。

### 4.3 上下文重建路径

`reloadTextureInPlace` 硬编码 6408 的反射调用必须替换：压缩纹理的重建 =
重新 `glGenTextures` + 按缓存的压缩数据 `glCompressedTexImage2D`（不再走原版
in-place load）。未压缩纹理维持现状。ManagedTextureEntry 需记录纹理形态
（压缩格式/级别数），供重建与显存估算共用。

### 4.4 磁盘缓存与现有 ssotex 缓存的职责调整

新缓存域：`mods/ssoptimizer/cache/textures-bc/<formatTag>/v1/`（与
TextureConversionCache 并列）：

- 键 = 源字节 SHA-256 + 尺寸 + mip 标志 + 压缩格式 + 编码器版本
  （`ENCODER_VERSION`；格式选择逻辑或编码器输出语义变更时递增，当前 2）；
- 容器：自研轻量头（MAGIC + 级别表 + 各级压缩块），zstd 二次压缩——**实测二压收益
  45~60%**（BC7 块 25%→10.9% 原始，游戏贴图大面积透明/纯色使块数据高度冗余），必须做；
  不采用 DDS 容器——mip 语义自定义更直接，DDS 的 FourCC/头兼容包袱无收益；
- 目录覆盖 `-Dssoptimizer.bctexcache.dir`；指纹/索引/内存 LRU/预热机制
  照搬 TextureConversionCache。

**对现有 TextureConversionCache（ssotex v4）的格式/职责调整**（实测驱动）：

1. **职责收窄**：ssotex 现状全量 1823MB（28753 文件，zstd 后 ≈ 原始 RGBA 的 39%）。
   BC 缓存是有损「最终形态」缓存，命中后不再需要同源的 RGBA 解码缓存——
   **可压缩纹理完成压缩后停止写入 ssotex**，ssotex 仅服务不可压缩类别
   （§3.4 排除面：小精灵/材质贴图/字体/程序化纹理）。全量落地后缓存目录合计
   约降至 500MB 量级（实测推算），且 BC 命中路径免去「zstd 解压整图 RGBA」的
   CPU 开销，直接上传压缩块。
2. **堆内存同比例受益**：BC 命中路径的驻留数据为压缩块（1B/px），不再驻留
   RGBA DirectBuffer（4B/px），Java 侧堆外内存占用同步降到 1/4。
3. **原子写入修复**（对现有 ssotex 同样适用）：实测抽样发现 34/400 个 ssotex 文件
   流式解压报错（疑似写入中残件）。两级缓存统一改为「临时文件 + fsync + rename」
   原子落盘，杜绝崩溃/中断留下半文件。
4. **估算口径**：`estimateTextureGpuBytes` 按纹理形态改算（BC7/BC3=1B/px，
   BC1=0.5B/px，mip 链 ×1.33），TSV 报表加 `compression` 列（none/bc1/bc3/bc7）。

### 4.5 GL 能力探测

新增 `TextureCompressionSupport`（sso-loading）：
- 探测 `GL_ARB_texture_compression_bptc`（BC7）与 `GL_EXT_texture_compression_s3tc`
  （BC3/BC1），静态缓存结果（参考 TextureDimensionSupport 模式）；
- RT 模式下经 bridge `glGetString(GL_EXTENSIONS)` 阻塞回读一次（罕见操作，可接受）；
- 探测失败/全不支持：整特性静默降级未压缩路径 + 一次 info 日志。

### 4.6 native 模块

新增 `libssoptimizer_texcompress`（挂在 sso-loading 域下
`modules/internal/sso-loading/native-texcompress/`）：

- 内嵌 bc7enc 族单文件库；接口：`nativeCompressBC7/BC3/BC1(pixels, w, h, mipLevels, quality)` →
  级别化压缩块数组；mip 链下采样也在 native 侧做（避免 Java 侧逐层拷贝）；
- `NativeRuntime.loadModule("texcompress")` 懒加载；构建改动三处
  （settings.gradle.kts include + 新目录 build.gradle.kts + sso-loading
  headerOutputDirectory 已有无需动）；
- 编码耗时入账诊断（每纹理压缩 ms、MB/s）。

### 4.7 诊断与验收基线

- 显存估算改算与 TSV `compression` 列见 §4.4 第 4 条；
- 验收指标：战役+殖民地对够场景 residentMiB 下降率（离线实测外推目标 ≥3×）、
  启动后 5 分钟内后台压缩完成率、二次启动纹理上传耗时对比；
- 与离线实验室基线（`.dev/texcompress-lab/REPORT.md`）对照留档。

## 5. 兼容性保证

1. **mod 贴图同路覆盖**：所有经 `TextureLoader.loadTexture` 的纹理透明受益；
   程序化贴图与生成型纹理（旁路清单见 §2）不压缩，行为不变。
2. **视觉回归防线**：BC7 对 UI/舰船/背景/行星贴图实测质量达标（PSNR 中位 37dB，
   大图 P5 ≥31dB）；小精灵/材质贴图/小尺寸 BC1 已按 §3.4 排除面规避；
   联调期用 ShipWeaponAtlas dumpdir + 图集页导出 A/B 对比。
3. **能力缺失降级**：GL 探测不过 → 全程现状路径；缓存损坏 → 指纹不匹配即重压，
   不留静默失败（日志必备）。
4. **配置面**：`ssoptimizer.texcompress.enable`（总开关，默认 true）、
   `.format`（auto/bc7/bc3，默认 auto）、`.quality`（fast/normal/high，
   默认 normal）、`.highQualityPaths`（路径分级质量：命中子串强制 high 档，
   默认空——背景/特效已由 excludePaths 默认排除面覆盖；质量档已入缓存键，
   调档后旧条目自动 miss 重压）、`.excludePaths`（命中路径完全不压缩，
   保持 RGBA8 上传，默认 `background,starscape,nebula,illustration,/fx/`）、
   `.bc1ForOpaque`（bptc 可用时全不透明大图也用 BC1，默认 false——
   省显存换画质，平滑渐变可能出现色带）、`.deferredPrepass`（deferred 贴图
   后台仅压缩预压，默认 true）；`system-properties.md` 同步。
   诊断：能力探测日志附带 VRAM 基线（`GL_NVX_gpu_memory_info` /
   `GL_ATI_meminfo`，VramProbe），Texture manager summary 周期性输出
   驱动侧真实显存水位（RT 模式与无扩展环境自动跳过）。

## 6. 实施阶段

| 阶段 | 内容 | 验收 |
|---|---|---|
| T1 | GL 能力探测（TextureCompressionSupport）+ 诊断基线（形态列/显存改算）+ bridge `glCompressedTexImage2D` 转发 | 日志输出探测结果；诊断报表含压缩列（全 none） |
| T2 | native-texcompress 模块（bc7enc 内嵌 + mip 链生成）+ 压缩缓存域 + `uploadConverted` 分流 + 后台压缩线程池 | 单元测试（压缩块尺寸/解码roundtrip 容差）；二次启动纹理走压缩上传；显存估算下降可见 |
| T3 | 图集页压缩 + `reloadTextureInPlace` 压缩形态重建 | dumpdir A/B 无渗色；显示模式切换后压缩纹理正常重建 |
| T4 | 预热/收尾：启动期缓存预热接入、诊断对照报告、文档与 system-properties 同步 | save_load_smoke + 长程测试无回归；residentMiB 对照留档 |

每阶段独立可部署；`ssoptimizer.texcompress.enable=false` 随时整体回到现状路径。

## 7. 风险与开放问题

- **深 mip 层图集渗色**：16px padding 在 level≥4 后不足，需在 T3 实测决定
  「限制图集 mip 层级」还是「加大 padding」（影响 UV remap 常量，改动面大，优先前者）。
- **编码耗时与磁盘成本**：首轮全量压缩实测估算 20~40 分钟（单线程，可并行），
  后台进行不影响游玩；zstd 二压后磁盘写入量小（~11% 原始）。
  缓存目录体积上限策略（是否加 LRU 清理）T2 定。
- **bridge 新增转发面**：`glCompressedTexImage2D` 入队快照语义与既有
  glTexImage2D 一致，但级别循环上传要小心命令顺序（同纹理按级别顺序追加即可）。
- **编码器 alpha 质量上限**：独立 bc7enc（modes 1/5/6/7）对硬边 sprite alpha 偏弱
  （实测 alpha 中位 37.4dB，低于 BC3 的 42.5dB）；若实机观感不达标，升级路径为
  bc7enc_rdo 内的 bc7e.ispc（全模式、更快更高质量，代价是引入 ispc 构建链）——
  T2 先用独立 bc7enc 上线，ispc 评估留作质量预案。
