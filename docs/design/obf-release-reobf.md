# RC8 的 obf 正式包与 dev 开发包

同一份 named 源码生成 `obf` 和 `dev` 两种构件。`obf` 供玩家正式使用，SSO 对游戏的所有引用在构建时写成净化后的 obf 名。玩家端的命名空间处理只做 JVM 格式净化，渲染、存档等 SSO 优化仍按用户配置运行。`dev` 供开发者调试，保留 `.20`（`18e67d2`）的 named 模组、named 游戏环境和现有调试流程。下文的 `resolveReleaseAbi`、`reobfRelease` 和 `verifyObfRelease` 为拟议任务。

## JDK 25 实测

测试在 Linux 的 OpenJDK `25.0.5.0.3` 上执行。Windows 行使用 Windows 版游戏字节码。每组读取 `starfarer_obf`、`starfarer.api`、`fs.common_obf`、`fs.sound_obf` 四个 JAR。入口测试使用 `java -Xverify:all --dry-run`。类探针逐个请求加载，再用 `MethodHandles.Lookup` 查找同一个不存在的静态方法，区分正常的 `NoSuchMethodException` 与底层的类格式或链接错误。测试没有运行游戏主循环。表内失败数按被探测的类计，包含其依赖类导致的连带失败。[原始统计与逐类日志](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/obf-jdk25-20261120/summary.tsv>)

| 游戏字节码来源 | 原样加载启动类 | 原样探测失败 | 通用修复后的结果 |
| --- | --- | ---: | --- |
| Windows 英文 RC8 | 拒绝 `new.super` | 1,255 / 6,382 | 6,382 个通过 |
| Linux 原生英文 RC8 | 拒绝 `do.new` | 1,227 / 6,382 | 6,382 个通过 |
| Windows 常规汉化 RC8 | 拒绝 `new.super` | 1,258 / 6,382 | 单改成员还有 3 个失败，修复调试信息后 6,382 个通过 |
| 本机中文 Linux 安装的核心 | 入口通过 | 277 / 6,382 | 6,382 个通过 |

- **关键词可以链接。** Windows 英文样本中有 147 个类末段名、1,405 个字段名、1,690 个方法名恰为 Java 保留字。实测先以 `javac --release 25` 对 named 游戏类型编译调用方，再用 Tiny Remapper 0.14.0 仅转换该调用方。在原版 obf 游戏上成功创建 `com.fs.graphics.A.class`，调用 `super` 方法并读写 `super` 字段，得到 `x=37 y=38 blue=39`。Java 源码继续用 named 就能正常开发。名称规则见 [JVMS §4.2](https://docs.oracle.com/javase/specs/jvms/se25/html/jvms-4.html#jvms-4.2)。
- **带点的成员会阻断类定义。** 同一英文样本有 2,741 个字段和 263 个方法违反 JVM 成员名规则，分布在 809 个声明类中。实际调用方的常量池含 `Object.super:()I` 时，Java 25 在进入 `main` 前报 `Illegal method name`。另一个调用方只取类型的类字面量，在解析游戏类型时因其声明 `return.super` 报 `Illegal field name`。仅修复调用方，仍在定义游戏类时失败。仅修复游戏，仍在定义调用方时失败。同时修复两侧后，调用方成功构造真实的 `BitmapFont` 并执行原先含点的方法，读回 `base=37 smooth=38`，与 named 对照一致。普通字段、方法名须非空，禁止 `. ; [ /`，普通方法名另禁止 `< >`，`<init>` 与 `<clinit>` 按 JVM 专用规则保留。[实际调用记录与复现命令](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/named-to-obf-jvm25-IT2GpnOQ/README.md>)
- **调试表也会导致定义失败。** 汉化 API 中两个类的 `LocalVariableTable` 有 6 个空名称。只改成员并重建常量池后仍有 3 个失败。按槽位给这些空条目生成合法调试名，保留其类型、作用域和索引，3 个失败全部消失。英文与汉化启动器在单改成员时都已能通过入口检查。[调试条目与槽位](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/obf-jdk25-20261120/zh-locals.log>)
- **实验使用零映射修复。** ASM 9.10.1 保留类名，把非法成员改为 `$sso$` 加原名 UTF-8 字节的 SHA-256 十六进制，在声明、调用和字段引用上使用同一规则。探针从空的 `ClassWriter` 重建常量池，保留字节码版本和已有验证帧。规范化阶段只读取本地 class 文件。实验输出是私有、仅含 class 的诊断副本。产品实现还须保留资源、加载域与签名语义。[修复探针](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/obf-jdk25-20261120/Normalize.java>) · [加载探针](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/obf-jdk25-20261120/LinkScan.java>)

## 两种发布包

| 构件 | 游戏与模组 | 映射来源 | 使用方式 |
| --- | --- | --- | --- |
| `SSOptimizer-Standalone-[VERSION]-windows-x64-obf.zip` | 游戏保持 obf。SSO 的 payload、host 和 coremod 使用目标 ABI 的 obf 游戏引用。传统 obf 模组按原版规则加载 | 构建机用私有 Tiny 完成 reobf。玩家端只带通用净化器 | 公开发布，支持断网冷启动 |
| `SSOptimizer-Standalone-[VERSION]-windows-x64-dev.zip` | 沿用当前 named payload、host、Mixin 和 ASM，保留源码断点、IDE 运行及现有 named 模组准备流程 | 构建时将与 named ABI 匹配的完整映射写入 `version/mappings/game-full.tiny.gz`，并把文件摘要纳入该包的签名清单 | 本地开发和内部调试。开发者在已有 RC8 安装上解压安装后即可离线准备 named 环境，默认不公开发布 |

Windows x64 打包任务用同一个实际版本号替换两个文件名中的 `[VERSION]`，一次生成两份 ZIP。`stageObf` 与 `stageDev` 使用独立的输入清单、暂存目录和签名清单。公开发布任务显式选取已通过 obf 审计的 ZIP，禁止按 `*.zip` 收集附件。两个包都从用户安装读取游戏 JAR。版本清单记录发行类型、游戏命名空间和 ABI，启动器据此校验 host 与 payload。两种安装分别使用自己的类缓存、启动清单、调试输出和存档实例。

`obf` 包保留原版所有合法游戏符号，只净化 JVM 拒绝的条目。净化算法的版本属于发行 ABI，构建机与玩家端必须一致。公开包排除完整映射、局部对照表、Mixin refmap、开发 JAR、修复后的游戏 JAR 和名称诊断报告。发行 class 只包含执行指令和注入所需的最终目标。

### 工作区 reobf 工具链

相邻的 SectorDevGradle 已有 `reobfJar`、`shadeObfJar` 和 `verifyObfJar`。`reobfJar` 在 `artifactMode=OBF` 时调用 NanoForge 的 `JarRemapCli single named-to-obf`，传入主 JAR 与映射；`verifyObfJar` 默认抽样 500 个类。建议复用其产物选择和任务入口，在 SSO 的发行任务内增加完整类层级、非法名处理、双 Mixin 配置和变换目标校验。普通模组的发布流程要直接取通过该校验的归档，避免把开发 fat JAR 又复制到 overlay。[现有 SDG 实现](../../../SectorDevGradle/sdg/src/main/kotlin/io/github/nanoforged/sdg/SdgModPlugin.kt#L185)

1. `resolveReleaseAbi` 读取目标安装的四个归档、开发用 named 归档及私有映射，锁定摘要和工具版本。对跨包继承、接口、桥接方法、合成成员、描述符、成员访问标志做双向核对。映射缺项须依据目标上的同名同签名声明确认。碰撞、多义或成员缺失必须让构建失败，并指出输入组件和调用位置。Windows 与原生 Linux 的实际混淆名不同，按游戏字节码 ABI 选择产物，同一 ABI 可供不同宿主 OS 使用。已验证等价的英、汉版本可共享产物。其余输入须单独验证，禁止根据主机 OS 或第三方模组身份猜测 ABI。
2. `reobfRelease` 先处理 SSO 自有 class，再与未修改的依赖装配。工作区使用 Tiny Remapper 和 mapping-io 解析 `named → obf` 并与非法名规则组合，直接写最终符号。源侧类层级来自 named 游戏和各模块，目标核验使用规范化 obf 核心。处理字节码类型、描述符、签名、异常、注解、栈帧、nest/inner、`MethodHandle`、`invokedynamic`、`ConstantDynamic` 及其引导参数。保留 SSO 自身 API、JDK、LWJGL 和其他库的符号，游戏 override 随真实声明变更。现有 `reverseProvider()` 可复用。现有归档转换器还会写入 named 清单并安装反射适配，不能原样反向调用。[MappingProjection](../../modules/internal/sso-remap/src/main/java/github/kasuminova/ssoptimizer/standalone/remap/MappingProjection.java#L119) · [ArchiveRemapper](../../modules/internal/sso-remap/src/main/java/github/kasuminova/ssoptimizer/standalone/remap/ArchiveRemapper.java#L149)
3. **Mixin 必须静态写入最终目标。** 对 `@Mixin(value/targets)`、注入方法、`@At`、`@Slice`、`@Shadow` 及 aliases、`@Accessor`、`@Invoker`、`@Overwrite` 和项目使用的 MixinExtras 按目标 owner 和描述符解析。成员只写名字时须证明在目标上唯一，或显式列出所有预期重载。生成时保留注入处理器自身名字，按 Mixin 前缀和接口规则单独处理 shadow/accessor。遇到工具不支持的选择器在构建期报错。**已在真实的存档输出流 Mixin 上确认**，裸 Tiny Remapper 留下 named 注解，启用 `MixinExtension` 后可改写目标类、构造器及方法选择器，但仍留下 `@Shadow(aliases="writtenBytes")`。原版目标没有这个字段名。发布构建须将别名解析为实际字段并以真实织入验证。[实测涉及的 Mixin](../../modules/internal/sso-save/src/main/java/github/kasuminova/ssoptimizer/mixin/save/CampaignSaveProgressOutputStreamMixin.java#L14) · [有无扩展的输出对照](</mnt/store/Games/ssoptimizer-standalone-validation-rc8/evidence/obf-jdk25-20261120/mixin/real-selector-output.txt>)
4. **ASM 比较字符串和资源单独处理。** `static final String` 会内联到调用方，修改常量定义类会遗漏注册点、匹配和发射的指令。构建端按语义登记游戏 `owner/name/descriptor`，将结果写回每个实际使用点。覆盖 RFB 注册、Mixin 配置和插件条件、按名称查找类或成员、服务资源和实际启用的访问规则。保留普通文本、第三方 API 和配置键。动态拼接的游戏目标须给出可验证的解析规则，禁止全局替换任意字符串。对依赖 named 开发 JAR 放宽可见性的调用，优先改用项目的 Mixin accessor 或 invoker，并在 obf 目标上验证调用的包、类加载器和访问权限。[SharedBootstrap](../../modules/internal/sso-app/src/main/java/github/kasuminova/ssoptimizer/bootstrap/SharedBootstrap.java#L103) · [GameMemberNames](../../modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/mapping/GameMemberNames.java#L3)
5. `verifyObfRelease` 从已装配的 obf 产物逐项解析声明、引用、访问及变换目标。运行真实 Mixin prepare/apply，比较每个注入点的命中数和变换后字节码，并分别跑渲染线程开关两种配置。现有校验读取 named 依赖，只验证目标类存在和部分 ASM 注册。它适合保留在开发构建。[现有校验任务](../../tools/verify-transform-targets.gradle#L11) · [校验器](../../tools/TransformTargets.java#L61)

### Mixin 的 refmap 与 ASM 的目标符号

Mixin 的 refmap 可以替代**部分注解字符串**的预转换。项目锁定的 `0.16.3+mixin.0.8.7` 在 `MemberInfo.parse` 中通过选择器上下文解析引用，在 `MixinConfig.remapClassName` 中通过 reference mapper 解析字符串类目标。建议在私有构建目录生成针对 SSO 注入点的 refmap，将其作为构建输入，按 Mixin 类和映射上下文解析 `@Inject(method=...)`、`@At(target=...)` 等受支持的引用，将结果写入 obf 包的注解。随后移除配置中的 refmap 引用。refmap 的键和值本身构成局部 named/obf 对照，当前分发限制同样适用。dev 在 named 目标上直接使用源码选择器。

对 `remap=false` 的现有注解，构建器必须逐项判定目标命名空间。需要转换的游戏选择器应纳入构建期生成规则，直接引用第三方库的选择器按原值校验。Mixin 的普通 JVM 类型引用、处理器参数和方法体指令仍走 reobf。`MixinTargetContext.findField` 按字段名和 aliases 查找，`findRemappedField` 使用环境的 `RemapperChain`。存档流的 shadow 别名须在构建期单独解析并验证。验收时用最终 obf 包在无 refmap 环境织入，同一份源码的 dev 包则在 named 环境命中相应注入点。[存档流 Mixin](../../modules/internal/sso-save/src/main/java/github/kasuminova/ssoptimizer/mixin/save/CampaignSaveProgressOutputStreamMixin.java#L14)

ASM 处理器直接使用字符串匹配和生成指令。建议把注册类、匹配成员和要发射的成员集中声明为带 `owner/name/descriptor` 的构建输入，为 dev 和 obf 分别生成只有本命名空间值的操作数。碰撞处理器需要同时更新注册类、`TARGET_METHOD`、`TARGET_DESC` 和 `CLAMP_DESC`。其中 helper 属于 SSO，但它的描述符含游戏类型。验证时把处理器实际应用到两份对应游戏类，检查 `FSTORE 16` 唯一命中，注入的 `INVOKESTATIC` 能链接到各自构件中的 helper。[碰撞处理器](../../modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/asm/combat/CollisionImpulseClampProcessor.java#L18)

### obf 玩家运行时

- **准备归档。** `doctor` 识别游戏字节码 ABI、规范化规则版本和对应发行组件。只读打开原始文件。普通 obf 模组按原版顺序加载，合法名称保持原拼写。有显式 named 声明的模组在启动前报告需要作者提供 reobf 产物。无声明的传统模组沿用原版 obf 约定。来源冲突、缺少必需 ABI 时给出具体归档、类或成员。所有第三方模组遵循同一规则。
- **修复必须早于消费。** 游戏类和模组字节码中的非法符号引用都要在 Mixin 读取目标和 JVM 定义类前完成处理。优先为原版游戏在实例缓存中准备 obf 视图，供 RFB、Mixin、Janino 以及类层级读取共用。对实际需要修复的模组按其定义域在受支持的加载路径修复。简单、合法的原版模组直接保留原件。保留加载顺序、`CodeSource`、包密封、签名验证、MR-JAR 选择和原加载器。签名条目须先按原 JAR 验证，遇到现有加载域无法安全交付所需变换时明确诊断。禁止剥去签名、拦截 JDK 所有类定义或给第三方模组加特判。保留系统域与游戏域的既有分工、`.19` 的通用自建加载器处理，以及 ASM/GL 先于行为 Mixin 的顺序。[StandaloneTweaker](../../modules/internal/sso-host-standalone/src/main/java/ssoptimizer/standalone/host/StandaloneTweaker.java#L36) · [UrlClassDefinition](../../modules/internal/sso-core/src/main/java/github/kasuminova/ssoptimizer/asm/render/UrlClassDefinition.java#L53)
- **约束修复范围。** 对实际声明和引用执行相同算法，包括继承调用、方法句柄及按 JVM 结构编码的成员。空局部变量名在配对的调试表中同步修正。合法符号如与编码目标在同一解析域内碰撞，须停止并给出诊断。对原名字符串仅在已确认的反射查找、原版序列化别名等位置处理。按实际 owner、成员类型和签名从本机 obf 定义取得所需信息。禁止随包携带 named 对照、全局替换文本或伪造 `Class.getName()`。游戏源码脚本和 Janino 按玩家 obf 类路径解析。直接使用 named 游戏内部名的源码需要作者提供适合 obf 的版本。[现有成员名检查](../../modules/internal/sso-remap/src/main/java/github/kasuminova/ssoptimizer/standalone/remap/BytecodeRepair.java#L21)
- **切换旧启动协议。** 当前 `prepare` 要求映射并生成 named 缓存，宿主初始化时再次加载映射，模组检查还会拒绝标注 obf 的 SSO payload。三处要同时切换。取消 named 专用的反射桥和 CodeSource 替换，保留启用列表、脚本类归属、勾选后重新准备和重启的功能。在新版实例缓存使用独立的命名空间、规则及 ABI 版本键，按输入摘要和顺序验证缓存。旧实例单独保留。[LaunchFiles](../../modules/internal/sso-standalone/src/main/java/ssoptimizer/standalone/launch/LaunchFiles.java#L63) · [ModJar](../../modules/internal/sso-standalone/src/main/java/ssoptimizer/standalone/mods/ModJar.java#L23) · [PreparedSources](../../modules/internal/sso-host-standalone/src/main/java/ssoptimizer/standalone/host/PreparedSources.java#L51)

## 存档、打包与发布验收

| 实施顺序 | 通过条件 |
| --- | --- |
| 冻结目标 ABI，打通首次启动 | 每个声明支持的字节码版本通过上述类探针。用隔离实例在 JDK 25 默认校验及 `-Xverify:all` 下启动。覆盖含点的成员、关键词成员、跨归档调用、汉化调试表。禁止依赖 `-noverify`。 |
| 构建与实际织入 | `reobfRelease` 同时覆盖 payload 与独立 host 中的游戏引用。常规模组与 overlay 若要公开发布，也必须走 obf 构建并验证其宿主运行 obf。当前 named NanoForge 开发产物单独保留。用最终 JAR 核对两种渲染模式、可选组件、ASM 发射成员、脚本编译、动态查找及通用加载器行为。 |
| 旧档与新档往返 | 对同一字节码 ABI 的原版 obf 存档，在独立槽验证加载、另存、冷启动读回，并验证原版能否读回目标兼容格式。对实际持久化的非法字段，在游戏存档序列化器按真实声明绑定原版 XML 名称和修复后的字段。覆盖别名、继承字段、类型属性、压缩战役和描述文件。旧 `.20` named 档只能在明确匹配的写档 ABI 下，使用私有工作区离线迁移或已授权的本地旧环境导出到新槽。现有 `import-save` 转换方向为 obf→named。禁止直接反转 XML 文本或覆盖原槽。未识别的槽保留原件并给出来源诊断。[HistoricalSave](../../modules/internal/sso-standalone/src/main/java/ssoptimizer/standalone/instance/HistoricalSave.java#L50) |
| 真实流程回归 | Windows 英文、常规汉化与已声明支持的 Linux 安装分别验证首次准备、缓存命中、标题、新建生涯进度、市场推进、战斗、另存、重新启动、切换模组、窗口失焦恢复。隔离使用 105 模组回归样本。对比玩家设置、勾选列表、原始游戏 JAR 与旧档摘要。 |
| 审计最终发布物并签名 | 分别审核、签名两个包，复核最终 ZIP 与各自的清单。对 `obf` 的公开发行入口递归检查 JAR、压缩资源和 class 常量，排除完整或局部映射、refmap、named 游戏和私有诊断。对 `dev` 校验包内映射与 named ABI 匹配。Windows 安装器按构件类型核验必需文件。在已有 RC8、无开发工作区、无旧缓存且断网的环境分别验收，`obf` 完成游戏启动，`dev` 仅用包内映射完成 named 准备并进入调试环境。[独立版打包](../../gradle/standalone-distribution.gradle.kts#L58) · [Windows 安装校验](../../packaging/standalone/windows/Manage.ps1#L62) · [普通模组打包](../../build.gradle.kts#L127) |

### GitHub 发布

在本地运行 `./gradlew packageStandaloneWindowsObf -Pssoptimizer.native.target=windows -Pstandalone.version=<版本>`，并按构建流程提供本地 ABI 输入和发行签名私钥。GitHub 仓库变量 `SSOPTIMIZER_RELEASE_PUBLIC_KEY_DER_BASE64` 保存发行者 RSA 公钥的 X.509 DER Base64 编码，由发行维护者通过独立渠道核对后配置。

为对应提交创建 `v<版本>` tag，用 `gh release create v<版本> --verify-tag --draft <obf ZIP>` 创建草稿并上传唯一附件。计算 ZIP 的 SHA-256，以 tag 为运行引用手动启动工作流。

```sh
sha256sum "$obf_zip"
gh workflow run release.yml --ref "$release_tag" -f sha256="$obf_sha256"
```

工作流校验草稿仅含与 tag 对应的 Windows x64 `obf` ZIP，再使用发行者公钥核验清单签名、逐项摘要和归档内容。工作流通过后会公开草稿。运行期间请保留该草稿及附件，重新打包时应替换草稿附件后重新触发校验。

现有 `.20` 的签名清单包含映射，须停止将该包作为新授权范围内的公开下载。后续发行使用新的版本号、缓存目录和签名清单。已有安装与旧存档保留在原处，回退通过切换本地版本与对应存档副本完成。
