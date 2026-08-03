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

所有引擎级 ASM 处理器与外部模组优化集合都支持 `-Dssoptimizer.disable.<key>=true` 单独禁用，读取点在 `bootstrap/SSOptimizerCorePlugin.java`（`registerIf` 第 133 行、`registerCompositeIf` 第 145 行、外部模组第 120 行）。**`combatstate` 处理器没有开关，直接注册**（第 85 行）。

引擎级 key（对应处理器，均在 `SSOptimizerCorePlugin.java:80-105` 注册）：

| 属性 | 对应处理器 | 作用 |
|---|---|---|
| `ssoptimizer.disable.sprite` | `EngineSpriteProcessor` | 跳过 Sprite 渲染优化 |
| `ssoptimizer.disable.bitmapfontrenderer` | `EngineBitmapFontRendererProcessor` | 跳过位图字体渲染优化 |
| `ssoptimizer.disable.texturedstrip` | `EngineTexturedStripRendererProcessor` | 跳过贴图条渲染优化 |
| `ssoptimizer.disable.contrailengine` | `EngineContrailEngineProcessor` | 跳过尾迹引擎渲染优化 |
| `ssoptimizer.disable.aigridquery` | `CollisionGridQueryProcessor` | 跳过碰撞网格 AI 查询优化 |
| `ssoptimizer.disable.smoothparticle` | `EngineSmoothParticleProcessor` | 跳过平滑粒子渲染优化 |
| `ssoptimizer.disable.detailedsmoke` | `EngineDetailedSmokeProcessor` | 跳过详细烟雾粒子渲染优化 |
| `ssoptimizer.disable.generictextureparticle` | `EngineGenericTextureParticleProcessor` | 跳过通用贴图粒子渲染优化 |
| `ssoptimizer.disable.launcherdirectstart` | `LauncherDirectStartProcessor` | 跳过启动器直启注入（配套运行时属性见「启动器」节） |
| `ssoptimizer.disable.textureloader` | `TextureLoaderPixelProcessor` | 跳过 TextureLoader 像素转换优化 |
| `ssoptimizer.disable.textureobject` | `TextureObjectBindProcessor` | 跳过 TextureObject 绑定优化 |
| `ssoptimizer.disable.loadingtext` | `LoadingUtilsTextProcessor` | 跳过加载界面文字优化 |
| `ssoptimizer.disable.linuxdisplayime` | `LinuxDisplayImeProcessor` | 跳过 Linux 显示层 IME 注入 |
| `ssoptimizer.disable.linuxeventime` | `LinuxEventImeProcessor` | 跳过 Linux 事件层 IME 注入 |
| `ssoptimizer.disable.linuxkeyboardime` | `LinuxKeyboardImeProcessor` | 跳过 Linux 键盘 IME 注入 |
| `ssoptimizer.disable.astdautomation` | `ASTDAutomationCombatPluginProcessor` | 跳过 ASTD 自动化战斗插件注入（配套属性见「自动化」节） |
| `ssoptimizer.disable.windowsdisplayime` | `WindowsDisplayImeProcessor` | 跳过 Windows 显示层 IME 注入 |
| `ssoptimizer.disable.tooltiptextfieldime` | `TooltipTextFieldFactoryProcessor` | 跳过提示框文本域 IME 注入 |
| `ssoptimizer.disable.settingstextfieldime` | `SettingsTextFieldFactoryProcessor` | 跳过设置页文本域 IME 注入 |
| `ssoptimizer.disable.textfieldimplime` | `TextFieldImplementationProcessor` | 跳过文本域实现 IME 注入 |
| `ssoptimizer.disable.originalfontstream` | `OriginalFontResourceStreamProcessor` | 跳过原版字体资源流替换（Composite，第 101-105 行） |
| `ssoptimizer.disable.resourcefilecache` | `ResourceLoaderFileAccessProcessor` | 跳过 ResourceLoader 文件访问替换（Composite） |
| `ssoptimizer.disable.caseinsensitiveresource` | `CaseInsensitiveResourceFallbackProcessor` | 跳过大小写不敏感资源回退替换（Composite） |

## DCR（DetailedCombatResults）优化

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.dcr` | `false` | 为 true 时跳过 DCR 优化集合整体注册（key 取自 `DcrModOptimizer.featureKey()`） | `modopt/dcr/DcrModOptimizer.java:26`，`bootstrap/SSOptimizerCorePlugin.java:120` |
| `ssoptimizer.disable.dcrzstd` | `false` | 为 true 时 DCR 优化集合不纳入 L2 Zstd 压缩处理器，保留 DCR 原生 Deflater | `modopt/dcr/DcrModOptimizer.java:29,41` |

## 字体

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.font.ttf.enable` | `true` | 启用 TTF 生成的字体覆盖（替代原版位图字体资源流） | `common/font/OriginalGameFontOverrides.java:71` |
| `ssoptimizer.font.ttf.dir` | 无（`null` → `<工作目录>/<mods>/ssoptimizer/fonts`） | 自定义 TTF 字体源目录 | `common/font/OriginalGameFontOverrides.java:165` |
| `ssoptimizer.font.ttf.debug` | `false` | 开启字体覆盖命中/未命中调试日志 | `common/font/OriginalGameFontOverrides.java:47,59,64,147` |
| `ssoptimizer.font.profile` | `"original-match"` | 字体生成 profile（`original-match` / `maple-ui` 等） | `common/font/OriginalGameFontOverrides.java:256` |
| `ssoptimizer.font.export` | `false` | 导出生成的 BMFont 产物与 manifest（A/B 检查用） | `common/font/FontArtifactExporter.java:25` |
| `ssoptimizer.font.export.dir` | 无（`null` → `<工作目录>/ssoptimizer-font-export`） | 指定导出目录；设置该属性本身即视为开启导出 | `common/font/FontArtifactExporter.java:23,56` |
| `ssoptimizer.font.runtimescale.enable` | `true` | 启用运行时缩放字体缓存（高 DPI 下生成高分辨率运行时字体图集） | `common/font/RuntimeScaledFontCache.java:404` |
| `ssoptimizer.font.screenscale.override` | 无（`null` 或非正数 → 自动解析） | 强制指定屏幕缩放倍率，用于字体图集生成 | `common/font/EffectiveScreenScale.java:44` |
| `ssoptimizer.font.rasterizer` | `"auto"` | 字体光栅化后端：`native` / `java2d` / `auto` | `common/font/NativeFontRasterizer.java:26` |
| `ssoptimizer.font.hint` | `"auto"` | 字体 hint 模式 | `common/font/NativeFontRasterizer.java:129` |
| `ssoptimizer.font.forceautohint` | `"auto"` | 强制 autohint 模式 | `common/font/NativeFontRasterizer.java:145` |
| `ssoptimizer.disable.fontcache` | `false` | 禁用生成的字体包持久缓存 | `common/font/FontPackCache.java:35` |
| `ssoptimizer.fontcache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/fonts/zstd/v6`） | 自定义字体包缓存目录 | `common/font/FontPackCache.java:181` |

## 纹理/加载

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.texturecache` | `false` | 禁用纹理像素转换磁盘缓存（Zstd 压缩，MD5 校验） | `common/loading/TextureConversionCache.java:58` |
| `ssoptimizer.texturecache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/textures/zstd/v3`） | 自定义纹理缓存目录 | `common/loading/TextureConversionCache.java:332` |
| `ssoptimizer.texturecache.memory.maxbytes` | `67108864`（64 MiB，下限 0） | 纹理缓存内存上限 | `common/loading/TextureConversionCache.java:347` |
| `ssoptimizer.disable.texturecache.warmup` | `false` | 禁用启动期后台把磁盘缓存预热到内存 | `common/loading/TextureConversionCache.java:191` |
| `ssoptimizer.disable.npot` | `false` | 禁用 NPOT 探测，纹理尺寸统一向上取 2 的幂 | `common/loading/TextureDimensionSupport.java:34` |
| `ssoptimizer.force.npot` | `false` | 强制启用 NPOT，跳过 GL 能力探测 | `common/loading/TextureDimensionSupport.java:37` |
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
| `ssoptimizer.loading.workerClass` | `github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadWorker` | 自定义延迟图片预加载 worker 类（须实现 `Runnable` 且有无参构造） | `common/loading/ParallelImagePreloadCoordinator.java:57` |
| `ssoptimizer.loading.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 延迟图片预加载并行度 | `common/loading/ParallelImagePreloadCoordinator.java:77` |
| `ssoptimizer.disable.parallelpreload` | `false` | 禁用并行图片预加载（并行度降为 1） | `common/loading/ParallelImagePreloadCoordinator.java:73` |
| `ssoptimizer.disable.resourcefilecache` | `false` | 禁用 ResourceLoader 文件元数据缓存（`exists`/`lastModified` 快照）。注意：既是 ASM 处理器开关（见上），又是运行时缓存开关，双重读取 | `common/loading/ResourceFileCache.java:80`，`bootstrap/SSOptimizerCorePlugin.java:104` |

## 声音加载

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.parallelsoundload` | `false` | 禁用声音文件并行预读 | `common/loading/sound/ParallelSoundLoadCoordinator.java:131,178` |
| `ssoptimizer.soundload.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 声音预读线程池大小 | `common/loading/sound/ParallelSoundLoadCoordinator.java:367` |
| `ssoptimizer.soundload.cache.maxbytes` | `134217728`（128 MiB，下限 0） | 声音预读内存缓存上限 | `common/loading/sound/ParallelSoundLoadCoordinator.java:348` |

## 脚本编译缓存

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.scriptcache.dir` | 无（`null` → `<mods>/ssoptimizer/cache/scripts/janino/v1`） | Janino 脚本字节码缓存目录 | `common/loading/script/JaninoScriptCompilerCoordinator.java:418` |
| `ssoptimizer.disable.scriptcache` | `false` | 禁用脚本字节码磁盘缓存（每次重新编译） | `common/loading/script/JaninoScriptCompilerCoordinator.java:96,138` |
| `ssoptimizer.disable.scriptprewarm` | `false` | 禁用首次脚本加载时的后台并行预编译 | `common/loading/script/JaninoScriptCompilerCoordinator.java:152` |
| `ssoptimizer.scriptcompile.parallelism` | `max(2, CPU 核数/2)`（下限 1） | 脚本预编译并行度 | `common/loading/script/JaninoScriptCompilerCoordinator.java:438` |

## 存档/XStream

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.disable.xstream.fastfieldaccess` | `false` | 禁用 XStream 字段访问快路径（回退纯反射访问） | `common/save/XStreamFieldAccessHelper.java:28` |
| `ssoptimizer.disable.txw2.queuedwriter` | `false` | 禁用 txw2 XML 批量队列写入器 | `common/save/Txw2CompactXmlWriterHelper.java:44` |
| `ssoptimizer.txw2.queuedwriter.queuecapacity` | `16384`（下限 256） | 批量队列事件容量 | `common/save/Txw2CompactXmlWriterHelper.java:50` |
| `ssoptimizer.txw2.queuedwriter.batchsize` | `256`（下限 16） | 批量队列批大小 | `common/save/Txw2CompactXmlWriterHelper.java:51` |
| `ssoptimizer.save.referenceid.cache.max` | `1048576`（1 << 20，向上取 chunk 边界） | XStream reference-id 预计算上限 | `common/save/XStreamReferenceIdHelper.java:237` |
| `ssoptimizer.disable.save.terrain.zstd` | `false` | 禁用地形 tile Zstd 写入格式（回退原版 Deflater 载荷） | `common/save/TerrainTileCompressionHelper.java:176` |
| `ssoptimizer.disable.save.progress.overlay` | `false` | 禁用保存/读档进度界面回放 | `common/save/SaveProgressOverlayCoordinator.java:80,119,144,161,197,227,250,269` |
| `ssoptimizer.save.progress.fps` | `0`（≤0 时按显示器刷新率） | 覆盖保存/读档进度界面目标刷新率（fps） | `common/save/SaveProgressOverlayCoordinator.java:374` |

## 渲染

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.render.allowFinish` | `false` | 战斗渲染循环中强制调用 `glFinish()`（调试用 GPU 同步） | `common/render/CombatStateTraversalHook.java:20` |
| `ssoptimizer.render.shipengine.enable` | `false` | 启用舰船引擎火焰渲染优化（默认关闭，须显式开启） | `common/render/ShipEngineRenderOptimizationToggle.java:21` |
| `ssoptimizer.textdiagnostics.enable` | `false` | 启用位图文本渲染诊断统计（glyph 四边形聚合） | `common/render/engine/TextRenderDiagnostics.java:117`，`TextLayoutDiagnostics.java:42` |
| `ssoptimizer.textdiagnostics.logintervalmillis` | `5000` | 文本渲染诊断汇总日志周期（≤0 停用） | `common/render/engine/TextRenderDiagnostics.java:121`，`TextLayoutDiagnostics.java:98` |

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

## native

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.native.path` | 无（`null` → `<mods>/ssoptimizer/native/<平台>/libssoptimizer.<ext>`） | 原生库路径覆盖；指定且为常规文件时优先使用 | `common/render/runtime/NativeLibraryResolver.java:16` |

## 日志

| 属性 | 默认值 | 作用 | 读取位置 |
|---|---|---|---|
| `ssoptimizer.logging.lunalib.level` | `"WARN"` | LunaLib 日志阈值（默认压制 INFO/DEBUG 噪音，设 `DEBUG` 恢复完整日志） | `common/logging/LogNoiseFilterConfigurator.java:51` |

## 已移除属性

以下属性随 **javaagent 运行时 remap 通道** 一并删除（coremod 化后相关类 `RuntimeRemapContext`、`RemappedClasspathInstaller` 已删除，当前代码无任何残留，传入会被忽略）：

| 属性 | 曾用语义 | 移除原因 |
|---|---|---|
| `ssoptimizer.deobf.full` | 全量 deobf 模式开关（agent 启动期全类覆写为反混淆类名） | 依赖 agent 的启动期全类覆写通道，随 remap 通道删除 |
| `ssoptimizer.remappedclasspath.exportdir` | remap classpath 导出目录（调试用） | 同上 |
