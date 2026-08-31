# SSOptimizer 系统属性清单

本文档盘点 SSOptimizer 运行时读取的全部 `-Dssoptimizer.*` JVM 系统属性，供配置参考与后续维护核对。

这些属性是用户可配置的稳定 ABI：**不收敛改名、保持用户兼容**。任何语义调整都应保留原属性名与默认值行为，需要行为变更时优先新增属性，破坏性改动只允许在显式声明的大版本迁移中发生。本清单对应 **coremod 化后（NanoForge coremod 取代 javaagent 通道）** 的代码状态，读取位置相对 `app/src/main/java`。

## 默认值约定（以代码为准）

| 读取方式 | 未设置时行为 |
|---|---|
| `Boolean.getBoolean(key)` | `false` |
| `System.getProperty(key, "x")` | `"x"` |
| `System.getProperty(key)` | `null`（各读取点的 null 语义见下，通常回退到自动探测或默认路径） |
| `Integer.getInteger(key, n)` / `Long.getLong(key, n)` | `n`（部分读取点还带下限钳制，见各条目） |
| `Boolean.parseBoolean(System.getProperty(key, "true"))` | `true` |

## ASM 处理器开关

所有引擎级 ASM 处理器与外部模组优化集合都支持 `-Dssoptimizer.disable.<key>=true` 单独禁用，读取点在 `bootstrap/SSOptimizerCorePlugin.java`（`registerIf` 第 117 行、`registerCompositeIf` 第 126 行、外部模组第 103 行）。**`combatstate` 处理器没有开关，直接注册**（第 79 行）。

引擎级 key（对应处理器，均在 `SSOptimizerCorePlugin.java:76-86` 注册）：

| 属性 | 对应处理器 | 作用 |
|---|---|---|
| `ssoptimizer.disable.launcherdirectstart` | `LauncherDirectStartProcessor` | 跳过启动器直启注入（配套运行时属性见「启动器」节） |
| `ssoptimizer.disable.textureloader` | `TextureLoaderPixelProcessor` | 跳过 TextureLoader 像素转换优化 |
| `ssoptimizer.disable.linuxdisplayime` | `LinuxDisplayImeProcessor` | 跳过 Linux 显示层 IME 注入 |
| `ssoptimizer.disable.linuxkeyboardime` | `LinuxKeyboardImeProcessor` | 跳过 Linux 键盘 IME 注入 |
| `ssoptimizer.disable.astdautomation` | `ASTDAutomationCombatPluginProcessor` | 跳过 ASTD 自动化战斗插件注入（配套属性见「自动化」节） |
| `ssoptimizer.disable.windowsdisplayime` | `WindowsDisplayImeProcessor` | 跳过 Windows 显示层 IME 注入 |
| `ssoptimizer.disable.tooltiptextfieldime` | `TooltipTextFieldFactoryProcessor` | 跳过提示框文本域 IME 注入 |
| `ssoptimizer.disable.settingstextfieldime` | `SettingsTextFieldFactoryProcessor` | 跳过设置页文本域 IME 注入 |
| `ssoptimizer.disable.textfieldimplime` | `TextFieldImplementationProcessor` | 跳过文本域实现 IME 注入 |
| `ssoptimizer.disable.originalfontstream` | `OriginalFontResourceStreamProcessor` | 跳过原版字体资源流替换（Composite，第 87-91 行） |
| `ssoptimizer.disable.resourcefilecache` | `ResourceLoaderFileAccessProcessor` | 跳过 ResourceLoader 文件访问替换（Composite） |
| `ssoptimizer.disable.caseinsensitiveresource` | `CaseInsensitiveResourceFallbackProcessor` | 跳过大小写不敏感资源回退替换（Composite） |

> 注：批次 1 已把 Sprite/BitmapFontRenderer/TexturedStrip/ContrailEngine/CollisionGridQuery/粒子三件套/TextureObject/LoadingUtils/LinuxEvent 的处理器迁移为 Mixin（`mixins.ssoptimizer.json` 对应条目），相关 `ssoptimizer.disable.*` 开关随 ASM 注册一并移除，不再可配置。

## DCR（DetailedCombatResults）优化

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.dcr` | `false` | 为 true 时跳过 DCR 优化集合整体注册（key 取自 `DcrModOptimizer.featureKey()`） | `modopt/dcr/DcrModOptimizer.java:25`，`bootstrap/SSOptimizerCorePlugin.java:103` |

## 字体

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.font.ttf.enable` | `true` | 启用 TTF 生成的字体覆盖（替代原版位图字体资源流） | `common/font/OriginalGameFontOverrides.java:71` |
| `ssoptimizer.font.ttf.dir` | 无（`null` → `<工作目录>/<mods>/ssoptimizer/fonts`） | 自定义 TTF 字体源目录 | `common/font/OriginalGameFontOverrides.java:165` |
| `ssoptimizer.font.ttf.debug` | `false` | 开启字体覆盖命中/未命中调试日志 | `common/font/OriginalGameFontOverrides.java:47,59,64,147` |
| `ssoptimizer.font.export` | `false` | 导出生成的 BMFont 产物与 manifest（A/B 检查用） | `common/font/FontArtifactExporter.java:25` |
| `ssoptimizer.font.export.dir` | 无（`null` → `<工作目录>/ssoptimizer-font-export`） | 指定导出目录；设置该属性本身即视为开启导出 | `common/font/FontArtifactExporter.java:23,56` |
| `ssoptimizer.font.screenscale.override` | 无（`null` 或非正数 → 自动解析） | 强制指定屏幕缩放倍率，用于字体图集生成 | `common/font/EffectiveScreenScale.java:44` |
| `ssoptimizer.font.rasterizer` | `"auto"` | 字体光栅化后端：`native` / `auto`（P4 起 TTF 路径仅 native，其它取值按 `auto` 处理） | `common/font/NativeFontRasterizer.java:26` |
| `ssoptimizer.font.hint` | `"auto"` | 字体 hint 模式 | `common/font/NativeFontRasterizer.java:129` |
| `ssoptimizer.font.forceautohint` | `"auto"` | 强制 autohint 模式 | `common/font/NativeFontRasterizer.java:145` |
| `ssoptimizer.disable.fontcache` | `false` | 禁用生成的字体包持久缓存 | `common/font/FontPackCache.java:35` |
| `ssoptimizer.fontcache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/fonts/zstd/v6`） | 自定义字体包缓存目录 | `common/font/FontPackCache.java:181` |
| `ssoptimizer.font.atlas.pageSize` | `2048` | TTF 动态字形图集单页边长（像素，GL_ALPHA8） | `common/font/atlas/DynamicGlyphAtlas.java` |
| `ssoptimizer.font.atlas.maxPages` | `16` | 图集全局页数上限，超限按 (face, bucket) 整组 LRU 淘汰 | `common/font/atlas/DynamicGlyphAtlas.java` |
| `ssoptimizer.font.atlas.debug` | `false` | 图集命中/未命中/页数/上传量诊断日志 | `common/font/atlas/FontAtlasDiagnostics.java` |
| `ssoptimizer.font.stroke.synthesize` | `true` | TTF 源下描边/边框在栅格化层合成（单 pass 剪影）；false 回退原版多 pass | `common/font/layout/TextLayoutEngine.java` |

## 纹理/加载

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.texturecache` | `false` | 禁用纹理像素转换磁盘缓存（Zstd 压缩，MD5 校验） | `common/loading/TextureConversionCache.java:58` |
| `ssoptimizer.texturecache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/textures/zstd/v4`） | 自定义纹理缓存目录 | `common/loading/TextureConversionCache.java:332` |
| `ssoptimizer.texturecache.memory.maxbytes` | `67108864`（64 MiB，下限 0） | 纹理缓存内存上限 | `common/loading/TextureConversionCache.java:347` |
| `ssoptimizer.disable.texturecache.warmup` | `false` | 禁用启动期后台把磁盘缓存预热到内存 | `common/loading/TextureConversionCache.java:191` |
| `ssoptimizer.disable.npot` | `false` | 禁用 NPOT 探测，纹理尺寸统一向上取 2 的幂 | `common/loading/TextureDimensionSupport.java:34` |
| `ssoptimizer.force.npot` | `false` | 强制启用 NPOT，跳过 GL 能力探测 | `common/loading/TextureDimensionSupport.java:37` |
| `ssoptimizer.texcompress.enable` | `true` | GPU 纹理压缩（BC 族）总开关；false 时整特性视为不可用（全程未压缩路径） | `common/loading/TextureCompressionSupport.java:29` |
| `ssoptimizer.texcompress.format` | `auto` | 压缩格式收窄：`auto`（bc7 优先、bc3 回退）/ `bc7` / `bc3`（强制 bc3 时即便 bc7 可用也用 BC3）；T2 生效 | `common/loading/TextureCompressionSupport.java:30` |
| `ssoptimizer.texcompress.quality` | `normal` | 后台压缩质量档：`fast` / `normal` / `high`，投递压缩任务时读取 | `common/loading/TextureCompressionScheduler.java:40` |
| `ssoptimizer.texcompress.highQualityPaths` | 空 | 高质量路径模式（逗号分隔子串，大小写不敏感）：命中的贴图强制 `high` 质量档，优先于全局 `quality`。默认空——背景/特效类贴图实测 high 档 BC7 仍有可见色阶，已由 `excludePaths` 默认排除面绕过压缩；本属性留给「宁可压缩也要省显存」的自定义场景 | `common/loading/TextureCompressionScheduler.java:47` |
| `ssoptimizer.texcompress.excludePaths` | `background,starscape,nebula,illustration,/fx/` | 压缩排除路径模式（逗号分隔子串，大小写不敏感）：命中的贴图完全不压缩，保持 RGBA8 上传。默认排除背景/插画/星云/特效类大面积平滑渐变贴图（实测色阶不可接受）；置空字符串恢复全量压缩 | `common/loading/TextureCompressionEligibility.java:45` |
| `ssoptimizer.texcompress.mode` | `background` | 压缩时机：`background`（后台线程压缩，首轮未压缩上传）/ `eager`（加载时同步压缩，首轮即压缩形态上传，首轮加载耗时显著增加，建议搭配 `quality=fast`） | `common/loading/TextureCompressionSupport.java:38` |
| `ssoptimizer.texcompress.hotreload` | `true` | 热重传：后台压缩完成后，已驻留的未压缩纹理在下一次绑定时原地升级为压缩形态 | `common/loading/LazyTextureManager.java:55` |
| `ssoptimizer.texcompress.bc1ForOpaque` | `false` | BC1 显存优先：BC7 可用时，实际像素全不透明的大图（最长边 ≥256）也选 BC1（画质换显存）；BC3 回退场景下非 FULL alpha 的大图恒走 BC1，不受此开关影响 | `common/loading/TextureCompressionSupport.java:38` |
| `ssoptimizer.texcompress.deferredPrepass` | `true` | 延迟上传（deferred）纹理的后台压缩预处理：不持有像素、等首绑的纹理提前在后台完成解码+转换+压缩，首绑直接吃压缩缓存 | `common/loading/TextureCompressionScheduler.java:44` |
| `ssoptimizer.bctexcache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/textures-bc/<formatTag>/v1`） | 自定义压缩纹理（SSOBC+zstd）缓存根目录（覆盖后仍按 formatTag/v1 分层） | `common/loading/CompressedTextureCache.java:41` |
| `ssoptimizer.bctexcache.memory.maxbytes` | `67108864`（64 MiB，下限 0） | 压缩纹理缓存内存上限 | `common/loading/CompressedTextureCache.java:42` |
| `ssoptimizer.disable.nativepngdecoder` | `false` | 禁用 JNI 原生 PNG 解码器 | `common/loading/NativePngDecoder.java:57` |
| `ssoptimizer.disable.lazytextureupload` | `false` | 禁用惰性纹理上传（启动只留元数据，首次绑定才传 GPU） | `common/loading/LazyTextureManager.java:296` |
| `ssoptimizer.lazytextureupload.minimalstartup` | `true` | 最小化启动纹理（managed 图形资源且 ≥ trackminbytes）延迟上传 | `common/loading/LazyTextureManager.java:318` |
| `ssoptimizer.lazytextureupload.minbytes` | `1048576`（1 MiB，下限 262144） | GPU 估算字节数低于此值的纹理不延迟上传 | `common/loading/LazyTextureManager.java:354` |
| `ssoptimizer.lazytextureupload.trackminbytes` | `65536`（64 KiB，下限 16384） | 纳入驻留跟踪的最小 GPU 字节数 | `common/loading/LazyTextureManager.java:359` |
| `ssoptimizer.lazytextureupload.idleunloadmillis` | `0` | 纹理空闲多少毫秒后卸载（0 = 不按空闲卸载） | `common/loading/LazyTextureManager.java:339` |
| `ssoptimizer.lazytextureupload.previewprotectmillis` | `300000`（5 分钟） | 舰船/空间站/武器预览纹理的额外保护期，不被空闲卸载 | `common/loading/LazyTextureManager.java:349` |
| `ssoptimizer.lazytextureupload.sweepintervalmillis` | `1000`（下限 250） | 驻留扫描周期 | `common/loading/LazyTextureManager.java:364` |
| `ssoptimizer.texturecomposition.reportintervalmillis` | `5000` | 纹理组成报告输出周期 | `common/loading/LazyTextureManager.java:369` |
| `ssoptimizer.texturemanager.logintervalmillis` | `15000` | 纹理管理器汇总日志周期 | `common/loading/LazyTextureManager.java:375` |
| `ssoptimizer.texturecomposition.reportfile` | `"ssoptimizer-texture-composition.tsv"` | 纹理组成报告文件路径 | `common/loading/LazyTextureManager.java:288` |
| `ssoptimizer.atlas.shipweapon` | `true` | 舰船/武器贴图动态图集总开关。settings.json graphics 段引用的贴图构建期整体排除（模组裸 UV 消费面）；仍入图集的贴图被模组经 `getTextureId` 取走时运行期回退独立纹理 id（栈帧消费者分类，日志 `ATLAS-FALLBACK` 按路径@调用链去重输出） | `common/render/atlas/ShipWeaponAtlas.java:62` |
| `ssoptimizer.atlas.shipweapon.dumpdir` | 无（`null` → 不导出） | 图集构建时把每页写成 PNG 到指定目录，供检查空间利用率 | `common/render/atlas/ShipWeaponAtlas.java:63` |
| `ssoptimizer.texture.tracepath` | 空（关闭） | 定点诊断：路径含该子串的纹理在 getTextureId/bindTexture 时输出 INFO 轨迹；特殊值 `ALL` 每路径记一次并附调用栈 | `common/loading/LazyTextureManager.java:79` |
| `ssoptimizer.loading.workerClass` | `github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadWorker` | 自定义延迟图片预加载 worker 类（须实现 `Runnable` 且有无参构造） | `common/loading/ParallelImagePreloadCoordinator.java:57` |
| `ssoptimizer.loading.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 延迟图片预加载并行度；Wave 3 起兼作 Spec DAG 加载与 Variant 解析（`SpecLoadScheduler` / `SpecStoreMixin.loadVariants`）的 Semaphore 最大并发闸门（任务跑在 VtWorkers 虚拟线程上，闸门约束加载期 CPU/磁盘争抢） | `common/loading/ParallelImagePreloadCoordinator.java:77`，`common/loading/SpecLoadScheduler.java` |
| `ssoptimizer.disable.parallelpreload` | `false` | 禁用并行图片预加载（并行度降为 1） | `common/loading/ParallelImagePreloadCoordinator.java:73` |
| `ssoptimizer.disable.resourcefilecache` | `false` | 禁用 ResourceLoader 文件元数据缓存（`exists`/`lastModified` 快照）。注意：既是 ASM 处理器开关（见上），又是运行时缓存开关，双重读取 | `common/loading/ResourceFileCache.java:80`，`bootstrap/SSOptimizerCorePlugin.java:104` |

## 声音加载

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.parallelsoundload` | `false` | 禁用声音文件并行预读 | `common/loading/sound/ParallelSoundLoadCoordinator.java:135,185` |
| `ssoptimizer.soundload.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 声音预读最大并发闸门（Semaphore 许可数；Wave 3 起预读任务跑在 VtWorkers 虚拟线程上，闸门约束磁盘带宽与在途内存——在途字节不计入 cache.maxbytes 账目） | `common/loading/sound/ParallelSoundLoadCoordinator.java:407` |
| `ssoptimizer.soundload.cache.maxbytes` | `134217728`（128 MiB，下限 0） | 声音预读内存缓存上限 | `common/loading/sound/ParallelSoundLoadCoordinator.java:365` |

## 脚本编译缓存

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.scriptcache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/scripts/janino/v2`，分离渲染模式为 `v2-rt`） | Janino 脚本字节码缓存目录 | `common/loading/script/JaninoScriptCompilerCoordinator.java:575` |
| `ssoptimizer.disable.scriptcache` | `false` | 禁用脚本字节码磁盘缓存（每次重新编译） | `common/loading/script/JaninoScriptCompilerCoordinator.java:122,177` |
| `ssoptimizer.disable.scriptprewarm` | `false` | 禁用首次脚本加载时的后台并行预编译 | `common/loading/script/JaninoScriptCompilerCoordinator.java:196` |
| `ssoptimizer.scriptcompile.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 脚本预编译最大并发闸门（Semaphore 许可数；Wave 3 起预热任务跑在 VtWorkers 虚拟线程上，闸门约束在途编译器实例的堆内存尖峰） | `common/loading/script/JaninoScriptCompilerCoordinator.java:598` |

## 存档/XStream

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.xstream.fastfieldaccess` | `false` | 禁用 XStream 字段访问快路径（回退纯反射访问） | `common/save/XStreamFieldAccessHelper.java:28` |
| `ssoptimizer.disable.txw2.queuedwriter` | `false` | 禁用 txw2 XML 批量队列写入器 | `common/save/Txw2CompactXmlWriterHelper.java:44` |
| `ssoptimizer.txw2.queuedwriter.queuecapacity` | `16384`（下限 256） | 批量队列事件容量 | `common/save/Txw2CompactXmlWriterHelper.java:50` |
| `ssoptimizer.txw2.queuedwriter.batchsize` | `256`（下限 16） | 批量队列批大小 | `common/save/Txw2CompactXmlWriterHelper.java:51` |
| `ssoptimizer.save.referenceid.cache.max` | `1048576`（1 << 20，向上取 chunk 边界） | XStream reference-id 预计算上限 | `common/save/XStreamReferenceIdHelper.java:237` |
| `ssoptimizer.disable.save.terrain.zstd` | `false` | 禁用地形 tile Zstd 写入格式（回退原版 Deflater 载荷） | `common/save/TerrainTileCompressionHelper.java:176` |
| `ssoptimizer.disable.save.progress.overlay` | `false` | 禁用保存/读档进度界面回放 | `common/save/SaveProgressOverlayCoordinator.java:85,124,149,166,202,232,255,275` |
| `ssoptimizer.save.progress.fps` | `0`（≤0 时按显示器刷新率） | 覆盖保存/读档进度界面目标刷新率（fps） | `common/save/SaveProgressOverlayCoordinator.java:418` |
| `ssoptimizer.save.modloadtiming` | `false` | 读档后处理阶段模组 `onGameLoad` 逐项计时（读档结束输出 TOP 15 汇总日志） | `common/save/ModPluginLoadTimer.java` |

## 渲染

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.render.allowFinish` | `false` | 战斗渲染循环中强制调用 `glFinish()`（调试用 GPU 同步） | `common/render/CombatStateTraversalHook.java:20` |
| `ssoptimizer.render.shipengine.enable` | `true` | 舰船引擎火焰合批渲染总开关（设 `false` 回退立即模式等价路径） | `common/render/ShipEngineRenderOptimizationToggle.java:21` |
| `ssoptimizer.render.shipengine.mode` | `"vbo"` | 引擎合批模式：`vbo` / `immediate`（GL 能力不足自动降级；曾有的 `instanced` 模式因游戏上下文内属性获取异常已移除） | `common/render/engine/GlCapability.java`，`EngineBatchImpl.java` |
| `ssoptimizer.render.shipengine.stats` | `false` | 引擎合批周期统计日志（每 300 次渲染输出实例数与 display list 回退计数） | `common/render/engine/EngineBatchImpl.java` |
| `ssoptimizer.render.warroomtasks.enable` | `true` | 指挥界面任务连线帧内合批（`TaskIconManager.render` 边界收集，单次提交） | `common/render/warroom/WarroomTaskLineBatch.java` |
| `ssoptimizer.render.spritebatch.stats` | `false` | Sprite 合批 P0 量化统计（只统计不改绘制）：战斗作用域内每 300 帧输出 quad 数/分组数/保序 run/禁区命中率 | `common/render/spritebatch/SpriteBatchStats.java`，`SpriteGroupStats.java` |
| `ssoptimizer.render.spritebatch.enable` | `true` | Sprite 流式保序合批总开关（false 全部透传原版 `SpriteRenderHelper` 路径） | `common/render/spritebatch/SpriteBatchImpl.java`，`SpriteBatch.java` |
| `ssoptimizer.render.shield.enable` | `true` | 护盾渲染优化开关（旋转递推 + 顶点缓存 + 合批） | `common/render/shield/ShieldRenderHelper.java` |
| `ssoptimizer.render.shield.algo` | `"recurrence"` | 护盾顶点算法：`recurrence` / `raycast`（对照实现，实测约 8 倍劣化） | `common/render/shield/ShieldArcGeometry.java` |
| `ssoptimizer.render.shipmasktess.enable` | `true` | Ship 蒙版三角化缓存开关（耳切 + WeakHashMap 缓存；false 走原 GLU 路径） | `common/render/tessellation/ShipMaskTessellationToggle.java` |
| `ssoptimizer.render.contrail.lod` | `true` | 战役尾迹视口距离 LOD 总开关（false 时视口外舰队尾迹照常渲染，回退原版行为） | `common/render/campaign/CampaignFleetPerformanceHelper.java` |
| `ssoptimizer.render.contrail.lod.margin` | `3000` | 尾迹 LOD 视口外扩边距：舰队位置超出可视矩形该边距时整条尾迹跳过渲染；默认远大于尾迹实际长度，屏幕边缘无可见截断 | `common/render/campaign/CampaignFleetPerformanceHelper.java` |
| `ssoptimizer.render.contrail.maxpoints` | `256`（≤0 关闭上限） | 单条战役尾迹点数上限：超限后 `ContrailEngineV2.addPoint` 丢弃新点（旧点照常老化移除），约束 hyperspace/冲刺补点膨胀；稳态百级点数不触发 | `common/render/campaign/CampaignFleetPerformanceHelper.java`，`mixin/render/ContrailEngineV2Mixin.java` |
| `ssoptimizer.textdiagnostics.enable` | `false` | 启用 v2 文本渲染诊断统计（render 接管的 pass/quad 聚合） | `common/render/engine/TextLayoutDiagnostics.java` |
| `ssoptimizer.textdiagnostics.logintervalmillis` | `5000` | 文本渲染诊断汇总日志周期（≤0 停用） | `common/render/engine/TextLayoutDiagnostics.java` |
| `ssoptimizer.renderthread.glErrorProbe` | `"off"` | RT 管线 GL 错误探针：`frame`（每帧末尾排空滞留 glGetError 并记 WARN）/ `command`（逐命令排空，重，仅定位用；该模式下录制侧把命令包装为 `ProbeSiteCommand` 捕获录制点堆栈，出错时输出去重后的录制点）。用于定位「滞留 GL 错误被模组健康校验（如 BoxUtil aux 线程 glInit）读到」类问题 | `common/render/queue/RenderQueueImpl.java`（`GL_ERROR_PROBE_PROPERTY`）、`ProbeSiteCommand.java` |

## 线程资源

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.workers.threads` | `max(cores-1, 1)` | 共享帧内工作池线程数（战斗 AI / 市场推进等帧内屏障任务共用，线程名 `SSOptimizer-Shared-Worker-N`）；非法值按默认处理并记 WARN | `common/concurrent/SharedFrameWorkers.java` |

> Wave 3（B/C 类线程迁移）：后台 IO 阻塞与一次性批任务统一走 `common/concurrent/VtWorkers` 虚拟线程门面（ResourceIndex 快照/预读、声音预读、Spec DAG/Variant 解析、Janino 预热、图集解码、字体 CJK 预热、txw2 队列写线程）。原「固定池大小」属性的取舍见各条目：保护真实资源瓶颈的（`soundload.parallelism` / `loading.parallelism` / `scriptcompile.parallelism`）保留为 Semaphore 最大并发闸门，默认值与含义不变；仅约束平台线程数的（ResourceIndex 预读池）直接移除。有意不迁移：`TextureCompressionScheduler`（MIN_PRIORITY + 让步语义）、`RenderQueueImpl` 渲染线程（GL 上下文亲和）、全部 shutdown hook（关停阶段虚拟线程调度器已停用），均已在对应类 javadoc/注释标注。

## 战斗逻辑

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.collisionGridBvh` | `true` | CollisionGrid 扁平 BVH 查询优化（false 回退 fastutil 网格收集路径） | `common/combat/ai/grid/CollisionGridBvhImpl.java` |

## 战役经济

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.econ.advance.interval` | `2` | 市场推进降频间隔：每 N 次经济推进以累计 dt 转发一次真实 `Market.advance`（1=逐帧转发即关闭；非法值按 1 处理并记 WARN 一次） | `common/campaign/econ/MarketAdvanceThrottleHelper.java` |
| `ssoptimizer.econ.advance.parallel` | `false` | 市场级并行化：降频判定通过的 NPC 市场提交共享工作池（`SharedFrameWorkers`）并行推进，玩家市场留主线程内联（SharedData 月报 / marketShareData / 构建事件三处共享写均以 isPlayerOwned 为门），`Economy.advance` RETURN 处帧内屏障；失败任务屏障处主线程串行重跑降级。模组自定义条件/产业/子市场插件若写跨市场全局状态属风险敞口 | `common/campaign/econ/MarketAdvanceParallelDispatcher.java` |

## IME 输入法

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.ime.enable` | `true` | IME 输入法功能总开关 | `common/input/ime/ImeProperties.java:19` |
| `ssoptimizer.ime.backend` | `"auto"` | IME 后端：`linux-xim` / `windows-imm` / `none` / `auto` | `common/input/ime/ImeProperties.java:23` |
| `ssoptimizer.ime.diagnostics` | `false` | 启用 IME 诊断日志 | `common/input/ime/ImeProperties.java:37` |

## 启动器

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.launcher.autostart` | `false` | 启动器自动直启游戏（跳过启动器 UI，调用原生静态启动方法） | `common/launcher/LauncherDirectStarter.java:26` |
| `ssoptimizer.launcher.autostart.res` | 无（`null` → 回退 `startRes`，再空则放弃） | 自动启动分辨率，格式 `WxH` | `common/launcher/LauncherDirectStarter.java:65` |
| `ssoptimizer.launcher.autostart.fullscreen` | `false`（回退属性，优先读 `startFS`） | 自动启动全屏标志 | `common/launcher/LauncherDirectStarter.java:37` |
| `ssoptimizer.launcher.autostart.sound` | `true`（回退属性，优先读 `startSound`） | 自动启动声音标志 | `common/launcher/LauncherDirectStarter.java:38` |

## 自动化

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.automation.enabled` | `false` | 游戏内自动化总开关 | `common/automation/AutomationConfig.java:39` |
| `ssoptimizer.automation.scenario` | `"arc_flare_aod7_basic"` | 自动化场景 ID | `common/automation/AutomationConfig.java:40` |
| `ssoptimizer.automation.outputDir` | `""`（空 → `<工作目录>/ssoptimizer-automation-output`） | 自动化输出目录（截图、telemetry） | `common/automation/AutomationConfig.java:41-44` |
| `ssoptimizer.automation.requireScreenshotFile` | `false` | 强制要求真实截图文件（验收验证用） | `common/automation/AutomationConfig.java:45` |
| `ssoptimizer.automation.saveload.saveDir` | 无（必填，否则报错退出） | `save_load_cycle` 场景的目标存档目录名（`<游戏目录>/saves/` 下）或绝对路径 | `common/automation/SaveLoadCycleDriver.java` |
| `ssoptimizer.automation.saveload.settleFrames` | `30` | 标题界面稳定帧数，达到后才触发读档 | `common/automation/SaveLoadCycleDriver.java` |
| `ssoptimizer.automation.saveload.saveAfterLoad` | `true` | 读档成功后是否立即执行完整保存（写侧基线采集） | `common/automation/SaveLoadCycleDriver.java` |

> `save_load_cycle` 场景（`ssoptimizer.automation.scenario=save_load_cycle`）：标题界面直接同步读档并计时，随后（默认）执行一次完整保存，遥测写入 `<outputDir>/saveload-telemetry.json`（含 `success`/`loadMs`/`unmarshalMs`/`saveMs`/`saveError`/`error`），随后自动退出（成功 exit 0，保存失败 exit 3）。用于存档读写性能基准与回归冒烟，与 mission 类场景互斥分发（见 `mixin/automation/TitleScreenAutomationMixin.java`）。写侧会真实覆写目标存档目录，`tools/save_load_smoke.sh` 会先把目标存档复制为 `_ssbench` 后缀的 scratch 副本再运行。

## native

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.native.path` | 无（`null` → `<mods>/ssoptimizer/native/<平台>/libssoptimizer.<ext>`） | 原生库路径覆盖；指定且为常规文件时优先使用 | `common/render/runtime/NativeLibraryResolver.java:16` |

## 日志

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.logging.lunalib.level` | `"WARN"` | LunaLib 日志阈值（默认压制 INFO/DEBUG 噪音，设 `DEBUG` 恢复完整日志） | `common/logging/LogNoiseFilterConfigurator.java:51` |
| `ssoptimizer.logging.vanilla.level` | `"WARN"` | 原版启动/加载期 INFO 噪音日志阈值（默认对 17 个高频原版 logger 保持 INFO 可见并装配消息级聚合过滤器：逐条压制「Loading …/Class … already loaded/Cleaned buffer …」等刷屏行，加载期结束后 flush 成「Loaded N <分类>」汇总行，WARN/ERROR、not-found 诊断与 SSOptimizer 自身日志不受影响；设 `INFO`/`DEBUG` 恢复完整原版加载日志） | `common/logging/VanillaLogNoiseConfigurator.java:66` |

## 已移除属性

以下属性随 **javaagent 运行时 remap 通道** 一并删除（coremod 化后相关类 `RuntimeRemapContext`、`RemappedClasspathInstaller` 已删除，当前代码无任何残留，传入会被忽略）：

| 属性 | 曾用语义 | 移除原因 |
|---|---|---|
| `ssoptimizer.deobf.full` | 全量 deobf 模式开关（agent 启动期全类覆写为反混淆类名） | 依赖 agent 的启动期全类覆写通道，随 remap 通道删除 |
| `ssoptimizer.remappedclasspath.exportdir` | remap classpath 导出目录（调试用） | 同上 |

以下属性随 **线程资源统合（Wave 1+2）** 删除（AI / Econ 独立工作池合并为 `SharedFrameWorkers` 共享池，当前代码无任何残留，传入会被忽略）：

| 属性 | 曾用语义 | 移除原因 |
|---|---|---|
| `ssoptimizer.ai.parallel.threads` | AI 并行工作线程数 | 两域独立池合并，统一为 `ssoptimizer.workers.threads` |
| `ssoptimizer.econ.advance.parallel.threads` | 市场并行工作线程数 | 同上 |
