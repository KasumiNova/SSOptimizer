# SSOptimizer

面向 Starsector 性能优化的实验工程骨架。

## 结构

- `app`：Java 25 主模块（包名 `github.kasuminova.ssoptimizer`）
- `native`：Gradle Native C++ 模块（`cpp-library`）

## 已配置能力

- Java Toolchain: 25
- C++ 编译: C++20（`-std=c++20 -O2 -fPIC`）
- JNI 预留：`app:compileJava` 会将头文件输出到 `native/src/main/headers/generated`

## 下一步建议

1. 在 `app` 中定义 JNI 接口（`native` 方法）。
2. 在 `native` 中实现 JNI 桥接，并输出 `.so`。
3. 在运行时装载 native 库并做性能关键路径验证。

## 开发环境基线文档

- [开发环境基线实现文档](docs/design/dev-environment-baseline-implementation.md)
- [新成员上手清单](docs/design/dev-environment-onboarding-checklist.md)
- [渲染链路 Mapping 工作流](docs/design/dev-environment-mapping-workflow.md)
- [开发运行档（Run Profiles）](docs/design/dev-environment-run-profiles.md)
- [故障排查手册](docs/design/dev-environment-troubleshooting.md)

## 纹理驻留诊断

- 纹理组成报告 (`ssoptimizer-texture-composition.tsv`) 现已默认启用。
	- 不再需要额外加 `-Dssoptimizer.texturecomposition.reportfile=...` 才会输出；默认相对 `starsector.log` 所在目录写出 `ssoptimizer-texture-composition.tsv`。
	- 如果你仍想改路径，可以继续使用 `-Dssoptimizer.texturecomposition.reportfile=/your/path/report.tsv` 覆盖默认值。
- `-Dssoptimizer.lazytextureupload.minimalstartup=true`
	- 默认开启。对绝大多数 `graphics/**` 贴图统一采用更激进的启动期延迟上传策略，而不是继续维护一组容易漏掉模组嵌套路径的目录白名单。
	- 当前会统一覆盖 `graphics/` 下的资源，只保留少量必须即时可用的 UI 类目录为 eager：`graphics/icons/*`、`graphics/ui/*`、`graphics/hud/*`、`graphics/cursors/*`、`graphics/fonts/*`、`graphics/warroom/*`。
	- 这类贴图即使来自 `graphics/<mod>/...` 之类的嵌套模组路径，也会优先只保留元数据 + zstd/像素缓存，避免启动期先冲上显存峰值、再靠空闲回收慢慢降下来。
	- 如遇到个别模组兼容性问题，可临时用 `-Dssoptimizer.lazytextureupload.minimalstartup=false` 回退到原先较保守的 defer 规则。
- `-Dssoptimizer.lazytextureupload.trackminbytes=65536`
	- 控制“即使立即上传，也要纳入空闲卸载跟踪”的最小估算显存字节数。
- `com.fs.graphics.Object.ö00000()`（textureId getter）现也会被 lazy hook 接管。
	- 这能避免其它模组绕过 `texture.Ø00000()`、直接拿 texture id 做自定义渲染时读到 `-1` 空贴图，甚至把游戏带崩。
	- 如果纹理仍处于 deferred 状态，首次读 id 会补做安全上传；为避免污染调用方状态，hook 会在上传后恢复原先绑定的纹理。
- `-Dssoptimizer.texturecomposition.reportfile=ssoptimizer-texture-composition.tsv`
	- 运行期间按固定间隔刷新，并在退出游戏时再次导出当前已跟踪纹理的组成报告，默认相对 `starsector.log` 所在目录解析。
	- 报告包含分组汇总与逐纹理明细，便于定位哪些 `graphics/**` 资源长期占用显存。
- `-Dssoptimizer.texturecomposition.reportintervalmillis=5000`
	- 控制运行期间报告自动刷新的间隔；设为 `0` 可禁用周期性落盘，仅保留显式/退出时导出。
	- 报告现会额外给出 `retention_advice` / `retention_reason`：
		- `required_now`：当前确实在热路径上，建议保留。
		- `optional_resident`：当前驻留但没有证据表明它是热点，可作为收紧空闲卸载策略的重点观察对象。
		- `not_needed_now`：当前并不驻留，或已经被逐出，说明它不需要此刻常驻显存。
- `-Dssoptimizer.texturemanager.logintervalmillis=15000`
	- 控制贴图管理摘要日志的输出间隔；设为 `0` 可禁用这类周期性管理日志。
	- 日志会定期输出：当前跟踪贴图数、驻留/非驻留数量、估算显存使用、可驱逐显存占用、上个周期清理数量、累计清理数量，以及当前显存占用最高的几个贴图分组。
- `-Dssoptimizer.runtimegl.logintervalmillis=15000`
	- 控制运行时 OpenGL 资源摘要日志的输出间隔；设为 `0` 可禁用该类周期性日志。
	- 这组日志会统计当前 live 的 `glTexImage2D/glTexImage3D` 纹理与 `glRenderbufferStorage` renderbuffer，分开给出 `runtimeTextureMiB`、`fileBackedTextureMiB`、`renderbufferMiB`、`peakRuntimeMiB`，并列出当前最占显存的运行时 owner，方便继续追查那部分“不在 TSV 里”的显存去向。
- `-Dssoptimizer.texturecache.memory.maxbytes=67108864`
	- 控制内存里保留的 zstd 压缩纹理副本预算，默认 `64 MiB`。
	- 当前 lazy/deferred 流程已经会在预加载阶段生成 zstd 转换缓存：未首次渲染的纹理不会占显存，只保留元数据 + zstd 缓存，等真正需要时再上传到 VRAM。
	- 如果你想让更多预加载贴图优先从内存 zstd 副本回填，而不是重新读盘，可以把这个预算调高。

## 日志降噪

- `-Dssoptimizer.logging.lunalib.level=WARN`
	- 默认值。压制 LunaLib / LunaSettings 的 DEBUG/INFO 噪音，但保留 WARN/ERROR。
- `-Dssoptimizer.logging.lunalib.level=DEBUG`
	- 调试开关。恢复 LunaLib 详细日志，方便排查设置加载问题。

## 原版字体 TTF 覆盖（实验性）


- 默认启用 `ssoptimizer.font.ttf.enable=true`
	- 为选定的原版字体路径动态生成 TTF-backed 的 BMFont 兼容资源，优先覆盖原版 `graphics/fonts/*.fnt` 中实际仍在使用的 `insignia*` / `orbitron*` 家族。
	- 当前已覆盖：`insignia12/12a/12bold/15LTaa/16/16a/17LTaa/17LTAaa/21LTaa/25LTaa/42LTaa` 与 `orbitron10/12/12bold/16/20/20bold/20aa/20aabold/24aa/24aabold`。
	- `victor*` 保持像素字体路线，不做 TTF 替换，改为优先走临近采样，避免被线性过滤抹糊；`small_fonts8`、`arial*`、`uni*`、`ursula*`、`futura*` 暂不处理。
	- 若需要快速做 A/B 或排查兼容性，可显式加 `-Dssoptimizer.font.ttf.enable=false` 临时关闭。
- `-Dssoptimizer.font.ttf.dir=/mnt/windows/Data/FONTS`
	- 指定 TTF 资源目录。默认就是该目录。
	- 默认 `original-match` 配置会优先使用原版随游戏分发的 `lte50549.ttf`、`orbitron-light.ttf`、`orbitron-bold.ttf`，并以 `MiSans-Regular.ttf` / `HarmonyOS_Sans_SC_Regular.ttf` 作为回退链，保证中英文字形混排。
	- 该方案只按字体路径覆盖原版核心字体；模组自带的独立字体资源仍然走旧 bitmap 路径。
- `-Dssoptimizer.font.profile=maple-ui`
	- 可选的调试 profile，不再是默认验收路径。
	- 启用后，原版被接管的 `insignia*` / `orbitron*` 会优先改用 `Maple UI.ttf` 与 `Maple UI Bold.ttf` 组装 atlas，适合只在需要强行放大视觉差异时做人工确认。
	- 默认值仍是 `original-match`，目标是尽量回到原版字体风格，同时用现代栅格化质量替换原版 bitmap atlas。
- `-Dssoptimizer.font.rasterizer=auto|native|java2d`
	- 新增字体栅格化后端选择。
	- `auto`（默认）：若 native 库已加载且系统存在 `freetype2`，优先使用 `native-freetype`；否则回退到 `java2d`。
	- `native`：强制请求 FreeType 后端；如果当前环境不可用，会在日志里提示并回退。
	- `java2d`：显式禁用 native 字形栅格化，方便做 A/B 对照。
	- 当前这一步还是“**native 生成 atlas + Java 保持原版渲染接入**”，而不是“**完全替换游戏运行时文字绘制器**”；目标是先把原版固定字号 `fnt` 的 glyph 质量做对。
	- 对 `victor*` 这类像素字体，不走 TTF 替换；重点是保持原始像素风格，并避免线性过滤带来的毛边和糊化。
	- `insignia*` / `orbitron*`（以及对应 runtime bucket atlas）现在默认禁用 mipmap，保留线性采样但避免小字号 UI 字体被 trilinear/mipmap 进一步抹糊。
	- 现在额外带一个实验性的 Phase 2 原型：当原版被接管字体在运行时出现非 `1.0x` 缩放桶时，会按 `0.125x` bucket 生成虚拟 runtime `.fnt + atlas` 并复用游戏原版 `com.fs.graphics.super.D` 载入。这条路径默认开启，可用 `-Dssoptimizer.font.runtimescale.enable=false` 关闭做 A/B。
- `-Dssoptimizer.font.hint=auto|light|normal|mono|none`
	- 控制 native FreeType 的 hint 策略。
	- `auto`（默认）：抗锯齿字体走 `light`，非抗锯齿字体走 `mono`。
	- `light`：更偏向现代 UI 小字号的灰度 hint；尽量稳住字形轮廓，不像 full hint 那样容易把横向字面挤变形。
	- `normal`：更传统的 full hint，对比 `light` 更适合做 A/B。
	- `mono`：强制单色位图 hint。
	- `none`：关闭 hint，仅保留基础栅格化，适合排查“到底是 hint 还是 atlas/scale 在影响观感”。
- `-Dssoptimizer.font.forceautohint=auto|true|false`
	- 控制是否强制使用 FreeType auto-hinter。
	- `auto`（默认）：当 `font.hint=auto/light` 且开启抗锯齿时，默认启用 `forceAutoHint=true`；其余情况关闭。
	- `true`：强制 auto-hint，适合追求更统一的小字号笔画控制。
	- `false`：优先使用字体自带 native hint，适合与 `normal` 模式对比。
	- 当前 native 路径默认还会禁用 embedded bitmaps，避免某些 TTF 在小字号突然切到内嵌点阵字形，导致 atlas 风格前后不一致。
- `-Dssoptimizer.font.export=true`
	- 将当前组装得到的 `.fnt + atlas png` 导出到本地，方便直接对照生成效果。
	- 默认导出到当前工作目录下的 `ssoptimizer-font-export/graphics/fonts/*`。
- `-Dssoptimizer.font.export.dir=/your/path/ssoptimizer-font-export`
	- 覆盖导出目录；设置该属性时会隐式启用导出。
	- 每个被接管的原版字体都会额外导出一个 `*.manifest.json`，里面会记录当前使用的后端（`native-freetype` 或 `java2d`）、后端细节（例如 `hint=light`、`forceAutoHint=true`）、最终选中的字体文件链、glyph 数量与分页信息，便于做 A/B 对照。
	- 这一步主要是为“方案 C”铺路：让生成结果和当前 raster backend 都保持可观测。
	- 如果你只是想确认“到底有没有换成功”，建议和 `-Dssoptimizer.font.profile=maple-ui` 一起用；manifest 里会明确显示 `Maple UI.ttf` / `Maple UI Bold.ttf`。
- `-Dssoptimizer.textdiagnostics.enable=true`
	- 开启运行时文字渲染摘要（默认关闭）。
	- 统计会挂在 `SuperObjectRenderHelper.renderGlyphQuad()` 热路径上，周期性输出 glyph quad 数、native/java 路径命中、shadow pass、缩放分布、小字形数量、最常见的 `scale bucket`，以及最常见的 glyph signature。
	- 目前的 `native-freetype` 仍属于“先生成 atlas，再按运行时 `scale` 采样”的阶段；所以如果游戏/UI 缩放把文字 draw scale 推到非 `1.0`，最终观感依然会受到位图放缩影响。
	- 这组统计主要用于下一阶段动态 glyph cache 设计：先看真实运行时文字分布和缩放桶，再决定 atlas 分桶、保留策略，以及是否要对零尺寸 glyph / 微小字号单独处理。
- `-Dssoptimizer.textdiagnostics.logintervalmillis=5000`
	- 控制文字渲染摘要日志的输出间隔；设为 `0` 可保留计数但禁用周期性日志。
	- 在当前重模组环境里，45 秒 `game` 烟测已经能稳定打出至少一轮完整摘要，适合做运行时样本采集。

## 烟测模式

- `./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher`
	- 仅验证启动器能否正常拉起，适合快速检查 `javaagent`、类修复与启动早期崩溃。
- `./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game`
	- 通过原版启动器自带的 `startRes` / `startFS` / `startSound` 直启分支直接进入正式游戏加载流程。
	- 默认注入：`-DstartRes=1920x1080 -DstartFS=false -DstartSound=true`。
	- 可通过环境变量覆盖：`SSOPTIMIZER_START_RES`、`SSOPTIMIZER_START_FS`、`SSOPTIMIZER_START_SOUND`。
	- 如需验证非默认 UI 缩放下的文字渲染路径，可额外设置 `SSOPTIMIZER_SCREEN_SCALE_OVERRIDE`（会在烟测开始前临时改写 `data/config/settings.json` 中的 `screenScaleOverride`，并在退出时自动恢复）。
	- 在当前模组较多的测试环境里，建议把 `timeoutSec` 提高到约 `45` 秒，以便真正走到 `BaseModPlugin.onApplicationLoad()`、字体替换、文字渲染摘要以及纹理报告导出阶段。

如果需要在正式加载阶段把纹理组成报告刷得更频繁，可组合使用：

- `JAVA_TOOL_OPTIONS='-Dssoptimizer.texturecomposition.reportintervalmillis=2000 -Dssoptimizer.texturemanager.logintervalmillis=5000 -Dssoptimizer.runtimegl.logintervalmillis=5000' ./tools/smoke_test_game_launch.sh /path/to/Starsector 35 game`
- `SSOPTIMIZER_SCREEN_SCALE_OVERRIDE=1.5 JAVA_TOOL_OPTIONS='-Dssoptimizer.textdiagnostics.enable=true -Dssoptimizer.textdiagnostics.logintervalmillis=2000' ./tools/smoke_test_game_launch.sh /path/to/Starsector 45 game`

