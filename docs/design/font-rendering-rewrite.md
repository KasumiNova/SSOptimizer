# 字体渲染体系重写设计（Font Rendering Rewrite）

状态：**P1-P4 已落地**（2026-08）。v2 管线（TextLayoutEngine + TextStreamEmitter + TTF 动态图集）
为唯一文本渲染路径并实机验证通过；P4 已拆除 legacy 路径（drawGlyph @Overwrite、
RuntimeScaledFontCache 反射换字体、`ssoptimizer.font.engine` 开关、fnt overlay、Java2D 降级链）。
剩余 P5（描边/阴影栅格化合成 A/B）未实施。

## 1. 背景与动机

重写前 sso-font 是「不重写渲染器」的渐进路线：资源层拦截（`OriginalFontResourceStreamProcessor` →
内存生成 BMFont）+ 渲染层 `drawGlyph` @Overwrite + 运行时换字体实例（`RuntimeScaledFontCache`）。
它解决了 CJK 与高清化，但积累了结构性问题（以下均为重写前的历史描述，相关代码已随 P4 拆除）：

1. **度量反向工程堆**：`encodedXAdvanceForRuntimeLayout`（xadvance = advance − xoffset）、
   `reconcileGlyphBox` 并集、victor 小写替换、`{`/`}` 空格化——每一条都是对原版混淆布局怪癖的
   事后适配，游戏版本/字体族一变就脆断。
2. **换字体实例方案违反项目铁律**：`RuntimeScaledFontCache` 用反射 + 签名猜测调用
   `BitmapFontManager`，属于全局规范禁止项，必须随重写一并移除。
3. **位图缩放问题域仍在**：`scale ≠ 1.0` 时仍是位图放缩采样，「高清」依赖 scale-bucket 重新生成
   整套图集，内存与生成成本随 bucket 数线性增长。
4. **渲染线程分离模式下 native 发射被禁用**（`glReady=false`），性能目标在默认配置打折。
5. **display list 缓存语义**仍是隐患温床（编译窗口按值捕获问题刚修过一轮）。

重写目标：**真正的高清字体渲染**——从 TTF 直接驱动，自研布局引擎复刻原版文本语义，
字形按目标像素尺寸精确栅格化进动态图集，发射走渲染线程 vertex stream，彻底脱离
immediate mode / display list / 位图缩放体系。

## 2. 原版渲染管线事实基线（已逆向证实）

来源：`runtime-text-phase2-entrypoints.md` + named 源码（`com.fs.graphics.font.BitmapFontRenderer`，
混淆名 `com.fs.graphics.super.Object`）。

- fnt 是标准 BMFont 文本格式，char id 即 Unicode 码点直接索引 `glyphs[]`；引擎硬编码单页；
  kerning 段可选。原版 insignia/victor 已含全量 CJK（6800+ 字形）。
- 整段文本在**单个** `glBegin(GL_QUADS)` 内逐字形发射，每字形 8 次 GL 调用；换行打断 batch；
  变色/选区高亮在 batch 内顶点级 `glColor4ub` 切换；下划线用 `_` 字形拉伸 quad。
- 阴影 = 整段第二遍偏移绘制（`shadowOffsetX/Y`）；边框 = 4 向 ±1px 偏移共 4 遍；
  `shadowCopies` 轮廓 = 每字形额外 N 个放大副本；compact font（victor/orbitron12condensed）
  整段画 3 遍。
- `len>10` 或 `len*(shadowCopies+1)>20` 且无选区时整段录制 display list（池化、每帧回收）。
- 全局缩放：`globalTextScale`（= 屏幕缩放）× `scale = 请求字号/名义字号`。
- CJK 断行：`drawTextWrappedCjk`（`Character.isIdeographic` + 标点禁则）。
- 特殊字形：`{`(123)/`}`(125) 在原版 fnt 中是 1×3 零像素占位符，**布局语义 = 空格**
  （xadvance 继承空格）；部分字形为 1×1 占位符需原样保留度量；victor 族小写渲染为大写。
- 缺失字形 fallback 到 `glyphs[63]`（'?'）。

## 3. 方案选型

### 3.1 候选对比

| 方案 | 原理 | 优点 | 缺点 |
|---|---|---|---|
| **A. 动态字形图集（推荐）** | FreeType 按目标像素尺寸精确栅格化 → alpha 图集 → 普通纹理 quad | 任意缩放下像素级精确；小字号质量优于 MSDF；无 shader 依赖，兼容固定管线与渲染线程桥；FreeType 已在 native-font 落地 | 需要图集管理与逐 size-bucket 缓存；首次出现字形有栅格化延迟（可异步） |
| B. MSDF/SDF | 离线/在线生成多通道距离场图集 + fragment shader 重建边缘 | 单图集全缩放；缩放零成本 | 需 shader 管线（渲染线程桥目前无 shader 路径）；CJK 6800+ 字形 MSDF 生成成本高（msdfgen）；小字号需精调 pxRange，质量仍不及精确栅格化 |
| C. Cosmic Text 等现成排版引擎 | Rust 排版栈经 JNI 接入 | 复杂文排（双向/ shaping）开箱即用 | 游戏文本是单语向简单排版，引入整套 Rust 工具链与 JNI 面得不偿失；HarfBuzz 级 shaping 对本项目无需求 |

**决策：方案 A（动态字形图集）为 v1 主干（已与用户定案：FT 后端）**。MSDF 作为远期可选升级
记录在案（若未来渲染线程获得 shader 路径且出现「单一分辨率图集全覆盖」诉求再评估），
Cosmic Text 明确不采用。与现代浏览器文本栈（Chrome = HarfBuzz 整形 + FreeType/DirectWrite
精确尺寸栅格化 + 字形图集）同构裁剪：去掉本项目无需求的 shaping 与平台原生后端。

### 3.1.1 离线观感验证（2026-08，已执行）

离线流水线：`.dev/font-offline-test/`（venv + freetype-py；msdfgen 静态库 + 自写 harness
`harness/glyph_msdf.cpp`；图表脚本 `chartgen.py`）。对项目全部 6 个 TTF（insignia/orbitron×3/
Oxanium/MiSans）在 12/16/24/48px 及 96px 放大档生成对比图：`out/chart_*.png`（暗底白字，
贴近游戏 UI 实景观感）。三路实现均为「该库最高质量档」：FT=LIGHT hinting 精确尺寸灰度 AA；
SDF=`FT_RENDER_MODE_SDF` 64px 图集重采样；MSDF=msdfgen 64px/pxRange4 图集 + median 重建。

实测结论：

1. **FT 精确尺寸**：全档位观感最佳。12/16px 因 hinting 边缘锐利、笔画对比清晰；96px 无瑕疵。
   方案 A 的观感上限成立。
2. **SDF@64**：全档位可用、笔画均匀；小字号略糊（无 hinting），96px 放大依然平滑仅尖角略圆。
   实现成本最低（FreeType 内置），可作廉价备选/降级路径。
3. **MSDF@64**：24/48px 边缘最锐利，但小字号笔画粘连（CJK 尤其明显），96px 放大暴露角部毛刺
   （未开 errorCorrection + 双线性放大所致）。要可用需 shader 管线 + errorCorrection 调参 +
   逐字号 pxRange 校准，投入产出比不支持 v1 采用。

方案 A 的另一个实证优势：96px 放大档下 SDF/MSDF 单图集路线已接近其质量极限，
而精确栅格化无此上限。

方案 A 的关键配套决策：**scale 量化分桶**。沿用最原型 `TextScaleBuckets`（0.5~4.0，步进 0.125），
栅格化按 `名义字号 × globalTextScale × bucket` 取整后的目标像素尺寸执行，bucket 内图集复用；
bucket 数量由 LRU 上限约束（见 §4.3）。

### 3.2 与现有 FreeType 资产的关系

native-font 已有 `nativeCreateFace/nativeRasterizeGlyph`（hint 四档、AA/MONO、度量输出完整）。
重写在其上**扩展**而非重起炉灶：

- 新增 outline/stroke 栅格化（`FT_Stroker`）——用于边框/阴影/outline 效果直接在栅格化层合成，
  避免原版「整段多遍绘制」的重复发射。
- 新增批量栅格化接口（一次 JNI 调用栅格化一组 codepoint），摊薄 JNI 边界成本。
- SDF 输出模式留扩展位，v1 不实现。

## 4. 架构分层

```
┌─ 接入层  ResourceLoader.openStream 拦截（保留，仅供给原版 BitmapFontManager 数据）
│          + BitmapFontRenderer.render() 族 @Overwrite（新接管点）
├─ 布局层  TextLayoutEngine（新）：复刻原版文本语义，产出 glyph run 序列
├─ 字形源层  GlyphSource 接口：
│            ├─ DynamicGlyphAtlas（TTF 字体：native FreeType 精确栅格化 + 动态图集）
│            └─ BitmapGlyphSource（mod 自带位图字体：原版 page 直读，无 TTF 源）
├─ 栅格化层  native-font（FreeType 扩展：stroke/outline、批量接口）
└─ 发射层  渲染线程 vertex stream（VertexArrayBatch），按纹理分组合并 draw call，
           无 display list、无 immediate mode
```

### 4.1 接管层：从 drawGlyph 上提到主循环

现有接管点 `drawGlyph` 已丢失 codepoint/font identity（phase2 文档结论）。重写改为
**@Overwrite `BitmapFontRenderer.render()` 及 `drawTextWrapped*` 族入口**，在保留
codepoint + kerning + scale 的层级完整接管：

- `render(String, x, y, ...)`、`drawTextWrapped`、`drawTextWrappedCjk` 等公开入口全部
  由新布局引擎接管；原版私有主循环不再执行。
- `BitmapFontRenderer` 的公开 API（setText/setFontSize/setTextColor/invalidateList 等）
  保持语义不变——mod 经游戏 API 拿到的 renderer 行为透明升级。
- display list 机制对该类**整体失效**（新实现不录制列表），同步消除一类编译窗口隐患。
- 原 `drawGlyph` @Overwrite 与新 @Overwrite 互斥，迁移时旧 Mixin 整段移除。

### 4.2 布局层：TextLayoutEngine

纯计算、零 GL 依赖，产出 `(glyphId, penX, penY, color, flags)` 序列。必须复刻的原版语义
（每条配单元测试，基准 = 原版 fnt 度量）：

1. `{`/`}` 及 1×1 占位符：零尺寸、xadvance 继承空格，不发射 quad（逻辑同
   `TtfBmFontGenerator.preservedSourceMetric`，上提到布局层成为一等语义）。
2. victor 族小写→大写映射（查字形前转换）。
3. 变色/选区高亮：run 级颜色切换（不再顶点级 glColor，改为 run 分段发射）。
4. 下划线：`_` 字形拉伸 quad（或布局层直接产矩形，二选一，实现时以原版像素对齐为准）。
5. 阴影/边框/outline/compact 多遍：布局层产出**多 pass 的 run 序列**（每 pass 一组偏移/颜色），
   发射层合并执行。**描边定案走栅格化层 stroke 合成**（见 §4.5），布局层只为位图字体
   （BitmapGlyphSource，无 TTF 源无法重栅格化）保留多 pass 复刻路径。
6. CJK 断行与标点禁则：复刻 `drawTextWrappedCjk` 规则。
7. kerning：查表前移——TTF 字体用 FreeType 的 kerning/（或保留原版 fnt kerning 表，实现时对比选择，
   以视觉一致为准）；位图字体用原版表。
8. 缺失字形 fallback `'?'`。
9. `globalTextScale × 请求/名义 scale` 换算进 size-bucket，布局坐标按浮点累积、发射前像素对齐
   （沿用 `snapToPixel` 策略，victor 类像素字体强制对齐）。
10. 动态缩放文本（伤害浮字等）：尺寸动画在 bucket 粒度内连续缩放，见 §4.6。

### 4.3 字形源层：GlyphSource

接口（sso-font 内部，经 sso-api 暴露与否视跨域需求——v1 预计仅 font 域内使用）：

- `GlyphBitmap request(codePoint, sizeBucket)` → 命中返回图集槽位（uv + 度量）；未命中同步
  栅格化（FreeType 微秒级，可接受）或标记 pending 并返回 fallback，由后台线程补齐后置脏。
- **DynamicGlyphAtlas**：key = (fontFace, sizeBucket, codePoint)；图集页 2048² 起步、按需扩页；
  LRU 以 (face, bucket) 为粒度整体淘汰；`glTexSubImage2D` 脏矩形上传（渲染线程侧执行，
  经现有 render queue 提交，逻辑线程只写 staging buffer）。
- **BitmapGlyphSource**：mod 自带 fnt 无 TTF 源时，直接读原版 page 贴图发射（即「不重写其数据源，
  只重写其发射路径」），保证 mod 字体不劣化。
- 字体身份判定：资源路径命中 `OriginalGameFontOverrides.OVERRIDES` 表 → TTF 源；否则位图源。
  覆盖表 v1 维持原版字体族范围，后续可开放 mod 注册 TTF 源（api 层加注册接口，本轮不做）。

### 4.4 发射层

- glyph quad 直接写入渲染线程 vertex stream（`VertexArrayBatch`），按图集纹理分组合并
  draw call；文本与 sprite 共用同一批发射基础设施。
- 阴影/边框多 pass 在同一纹理分组内顺序追加，批次边界自然对齐，**顺序确定性由 stream 顺序保证**
  （不再有 display list 按值捕获窗口）。
- 图集贴图需纳入上下文代际管理（显示模式切换重建）——现有图集纹理未接入
  LazyTextureManager 代际重建是已知遗留，本轮一并补。

### 4.5 描边毛边：根因与对策（已定案）

原版描边的两种实现（BitmapFontRenderer 逆向事实）：

- **border 模式**：整段文本以 ±1px 四向偏移共画 **4 遍**；
- **shadowCopies/outline**：`drawGlyph` 内每字形额外画 N 个按 `shadowScale` **放大**的副本。

毛边根因：这两种都是「同一张位图 alpha、多个偏移/缩放副本叠加混合」。副本的偏移量是
**位图像素空间**的整数，落到屏幕上经 `scale ≠ 1` 映射后不对齐目标像素格——每个副本各自被
双线性采样一遍，边缘覆盖率互相错位叠加，亮度沿轮廓周期性强弱起伏，视觉上就是毛边/阶梯感；
放大副本还会继承底图的低分辨率块状伪影。这是多 pass 位图描边的结构性缺陷，调 blend/对齐
参数只能缓解不能消除。

对策（TTF 源字体，v1 定案）：**描边在栅格化层合成**，不再多 pass 叠位图——

- native-font 用 `FT_Stroker` 对轮廓按目标像素尺寸的描边宽度扩张，栅格化出「描边剪影」
  字形（FreeType AA，边缘覆盖率精确到亚像素）；
- 发射时两个 quad：描边剪影 quad 用描边色（通常黑）垫底，填充字形 quad 用文本色盖顶。
  两者都在**精确目标尺寸**下栅格化，无偏移采样、无缩放副本，毛边从根上消除；
- cache key 增加描边宽度档位（量化到 0.5px 步进），同色异宽的描边字形各占槽位；
- 阴影（单纯偏移黑副本）维持「第二 pass 偏移发射」，但偏移换算改在**屏幕像素空间**取整，
  消除位图空间偏移的错位采样；
- 位图字体（mod 自带）无法重栅格化，保留多 pass 路径，观感与原版持平。

### 4.6 动态缩放文本（伤害浮字等）

问题：`scale` 逐帧连续变化的文本（伤害浮字缩放动画、界面缩放过渡），若按瞬时 scale 逐帧
换 size-bucket，会在 bucket 边界跳变且栅格化/缓存被动画打爆。

对策（按是否可预见缩放范围分两档）：

1. **超采样降采样（默认，无 shader 依赖）**：对已知有缩放动画的文本类别，按动画幅度上限
   取 bucket（如动画范围 1.0~2.0 → 直接栅格化 2.0 档），播放期间向下缩放采样。位图降采样
   观感良好（等效 2× 超采样），升采样只在超过上限时发生，退化温和。
2. **bucket 吸附 + 上限钳制（其余动态文本）**：瞬时 scale 吸附到最近 bucket，仅当超出
   bucket 覆盖带才重栅格化；连续动画自然收敛到少数几个 bucket。

不在 v1 引入 SDF 字形解决此问题：固定管线无 shader 路径，SDF 平滑重建必须 shader
（smoothstep + fwidth），无 shader 只能用 alpha test 硬边，观感反而不如超采样降采样。
渲染线程桥未来若获得 shader 能力再评估（与 MSDF 升级评估合并）。

**bucket 需求面与显存估算（论证备查）**：bucket key = 名义字号 × 屏幕缩放，而名义字号是
游戏固定枚举（每字体族 5~7 档）、屏幕缩放在一次会话内基本不变、栅格化按需惰性——实际活跃
bucket ≈ 每字体族 5~7 个，而非理论上限 28 个，无需为「多份样本」担心。显存量级：CJK 全量
6800 字形 × 24px 档 8-bit alpha ≈ 4MB/bucket，2.0 屏幕缩放翻倍至 ~16MB/bucket；全族全档位
打满也仅几十 MB，相对游戏贴图图集（数百 MB）是零头，另有 (face, bucket) 粒度 LRU 淘汰兜底。
小字号不走「单份高分辨率样本降采样」的原因：hinting 信息在降采样中被平均掉、笔画发虚
（离线图表已实测 SDF@64→12px 明显软于 FT 精确 12px，位图降采样同理），小字号必须精确栅格化。

## 5. 兼容性保证

1. **特殊文本语义**：`{`/`}` 空格化、1×1 占位符、victor 大小写、`?` fallback——布局层逐项复刻
   + 单元测试（基准度量取自原版 fnt，测试直接调布局引擎验证 run 序列，不做纯源码 contain）。
2. **模组接入点**：
   - 经游戏 API（`Fonts`/`BitmapFontRenderer`）的 mod 文本：透明升级，无需适配。
   - mod 自带字体路径：走 BitmapGlyphSource，行为不变。
   - LunaLib/GraphicsLib：无自带文本渲染器的证据，均走游戏 API，天然覆盖。
3. **资源层双轨退役（P4 已执行）**：`game-fonts/fnt/` 部署期覆盖原版文件的 overlay 机制
   （与汉化包互踩的来源）已随新管线下线——fnt 数据改为运行时内存供给（保留 openStream 拦截），
   游戏根目录不再被覆写，`game-fonts/fnt/` 仅保留作测试 fixture。
4. **反射清除（P4 已执行）**：`RuntimeScaledFontCache` 及其反射调用已整体删除。
5. **配置迁移**：`ssoptimizer.font.ttf.enable/profile/rasterizer` 等属性保留语义；
   `runtimescale.*` 族随 RuntimeScaledFontCache 删除；新增 `ssoptimizer.font.atlas.*`
   （页尺寸/淘汰上限）与描边合成开关。`system-properties.md` 同步更新。

## 6. 用户自定义字体（远期功能，本轮不实施）

在重写管线之上向用户开放「替换游戏内字体」能力。设计要点：

- **配置载体**：`mods/ssoptimizer/font-overrides.json`（或并入既有 profile），声明
  原版字体族（insignia/orbitron/victor 各字号）→ 用户 TTF 路径的映射，以及 CJK 回退链
  （默认 MiSans，可替换为任意用户字体）。留空 = 现行 `original-match` 行为。
- **字体源目录**：用户字体放 `mods/ssoptimizer/fonts/` 子目录，与打包字体同机制解析；
  文件缺失/损坏时记日志并回退该族默认映射，不留静默失败。
- **缓存失效**：FontPackCache 指纹已含字体文件 SHA-256，用户换字体后自动失效重建，
  无需手动清缓存。
- **约束**：用户字体仅改变字形源，不改变原版度量语义——`{`/`}` 空格化、占位符保留等
  布局层规则与字体无关，保证 UI 不错位；度量差异（xadvance 等）按新字体真实值布局。
- **UI 入口（更远期）**：游戏内设置面板（可挂 LunaLib 设置页或自建），初版仅配置文件。

## 7. 实施阶段（建议）

| 阶段 | 内容 | 验收 |
|---|---|---|
| P1 | TextLayoutEngine 纯计算实现 + 原版语义测试套件（§4.2 全部条目）；BitmapGlyphSource 位图直发路径 | ✅ 完成 |
| P2 | 发射层接入 vertex stream + `render()` @Overwrite 切换（位图字体先行，TTF 字体暂走旧路径） | ✅ 完成 |
| P3 | DynamicGlyphAtlas + native FreeType 扩展（批量/stroke）+ TTF 字体切换 | ✅ 完成 |
| P4 | 旧路径拆除：drawGlyph @Overwrite、RuntimeScaledFontCache（反射）、fnt overlay、双后端降级链 | ✅ 完成（2026-08） |
| P5 | 描边/阴影栅格化合成（多 pass → 单 pass）A/B 优化 | 未实施 |

每阶段独立可部署、可回滚（配置开关切回旧路径，开关随 P4 完成移除——已执行，`ssoptimizer.font.engine` 已删除）。

## 8. 风险与开放问题

- **像素级一致的验证成本**：阴影偏移、宽高比补偿等原版怪癖（`fallbackRenderGlyphQuad` 逐字节
  复刻过一轮）需逐项迁移到布局层测试基准；建议先用位图路径（P1/P2）锁定布局正确性，再动字形源。
- **首次栅格化卡顿**：CJK 大字符集冷启动时可预栅格化常用字（按原版 fnt char 表预热），
  或接受首帧 pending-fallback；实现时按诊断数据定。
- **渲染线程 shader 路径**：若未来上 MSDF，需要先在渲染线程桥建立 shader 程序管理能力，
  属独立前置工程。
