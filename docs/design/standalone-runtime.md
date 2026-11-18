# SSOptimizer Standalone 独立启动设计

状态：待实施。目标平台为 Starsector 0.98a-RC8、Java 25、Windows x64 与 Linux x64。`NanoForge` 和 `Standalone` 是两种互斥的启动方式，使用同一次构建生成的优化代码。

## 发行方式

建议正式名称用 **Standalone**。玩家将独立包解压到游戏目录，运行随包的 Windows 或 Linux 入口。启动器从本地游戏安装生成 named 环境，随后启动游戏。玩家无需安装 NanoForge、NanoLauncher、Gradle 或开发用 Maven 仓库。沿用 RFB 的系统加载器、LaunchWrapper 以及 Mixin 自带的 LaunchWrapper 服务；这些是随包锁定版本的库。生产启动不使用 `premain` 或 `Instrumentation`：JVM agent 无法靠重定义已按旧名请求的类来建立 named 类路径，也不负责 Mixin 的字节码查询或类加载器间的可见性。

独立版支持游戏模组及 SSOptimizer 自身的完整变换能力。依赖 NanoForge 插件 API 或其 binpatch 的其他 coremod 应选 NanoForge 发行版；启动前报告识别到的依赖和所属模组，禁止静默跳过后进入同一存档。允许同一原始安装保留两种启动入口，但每次运行只能选择一套宿主、一个优化负载和一份类定义。Standalone 的升级与卸载只操作自己的目录和入口，不替换游戏 jar、现有启动脚本或存档。

## 模块与依赖

| 模块（规划） | 职责及依赖 |
| --- | --- |
| `sso-api`、`sso-core`、功能模块 | `sso-api` 使用纯 Java 约定；游戏模块通过独立的 named 编译依赖约定取得游戏 API/引擎和需要的 ASM/Mixin。移除共享构建约定向所有模块注入的 NanoForge/RFB。功能模块继续经 `sso-api` 和 `sso-core` 协作。 |
| `sso-app`（共享装配） | 保留游戏 `ModPlugin`、跨模块服务、处理器注册、渲染队列、调试开关和一份 Mixin 配置；从 `SSOptimizerCorePlugin.onLoad` 提取宿主无关的安装动作，注入系统域处理器注册入口。两种宿主均在首次加载目标类前安装一次。 |
| `sso-host-launchwrapper` | 两种宿主共用现有两个 `IClassTransformer` 的 LaunchWrapper 入口，委托同一份 `HybridWeaver` 分发和 GL 改写代码。保留 NanoForge 元数据引用的入口全限定名，按需要移动入口和实现的模块归属。只在这个边界编译依赖 LaunchWrapper，功能实现不依赖加载器。 |
| `sso-host-nanoforge` | 独占 `INanoCorePlugin`、`CoreModContext`、`SystemAsmBridge`、SDG 的 NanoForge 打包插件及 coremod 元数据。维持原有部署路径、产物名和宿主提供依赖的方式，不重复打包 RFB、ASM 或 Mixin。 |
| `sso-remap` | 无游戏或加载器运行依赖。读取版本化映射，构建层级索引，生成 named 游戏与模组归档，并提供有来源命名空间的单类转换。使用 mapping-io/Tiny Remapper 处理 Tiny v2 和整组归档；单类入口与归档使用同一成员解析和重写规则，发布前对照结果。 |
| `sso-standalone` | 提供安装检查、缓存、启动 CLI、RFB 插件及 `ITweaker`；依赖 `sso-remap`。发布包分开存放系统域 bootstrap 与 Launch 域优化负载，避免 tweaker 包排除规则覆盖 Mixin 类。 |

[现有编译约定](../../buildSrc/src/main/kotlin/ssoptimizer-module.gradle.kts)将 SDG、NanoForge、RFB、ASM、Mixin 一并加入子模块；[现有装配脚本](../../modules/internal/sso-app/build.gradle.kts)还将运行时 jar 展开并剔除 multi-release 条目。这两处应分别拆分。Standalone 单列宿主库（RFB 1.0.12、ASM 9.8、Fabric Mixin 0.16.3+mixin.0.8.7、匹配的 MixinExtras、日志桥及其运行依赖），保留其原始服务描述与多版本条目。业务负载、JNI 库和模组数据按平台复用，游戏 jar 只参与编译或用户本地准备。发布任务分别检查 Gradle 解析图、最终包内容和类引用，尤其要检查织入字节码中的调用 owner；Standalone 产物不应出现 `io.github.nanoforged.api` 或指向 NanoForge 实现的 hook。

建议新增 `packageStandaloneLinuxZip`、`packageStandaloneWindowsZip`，保留现有 NanoForge 打包任务。独立包在游戏根新增启动脚本，私有 `ssoptimizer-standalone/` 目录放 bootstrap、`lib/`、版本清单、映射和 `cache/`。游戏仍通过 `mods/ssoptimizer` 的 `mod_info.json` 发现 `SSOptimizerModPlugin` 和游戏数据，JNI、字体及 Windows 的 FreeType 依赖保留原模组目录布局。共享优化 jar 只在 Launch 的 URL 集合中挂载一次；两种版本共用安装时先确认该负载的版本和哈希，存在不兼容的旧包就报告具体路径。升级时分别记录共享文件与宿主私有文件的归属，卸载一个宿主时保留另一个仍需的共享文件。

## 本地生成 named 环境

1. **锁定输入。** 发布清单为每个受支持的游戏输入集记录版本、四个游戏 jar 的 SHA-256、来源命名空间、Tiny v2 映射摘要、编译用 named ABI 版本、工具和后处理版本。四项为 `starfarer_obf`、`starfarer.api`、`fs.common_obf`、`fs.sound_obf`；公开 API 中保留原名的类也参加一致性检查。宿主 OS 决定启动路径和 native，jar 指纹决定映射；Linux 安装也可能使用 Windows 版游戏字节码。未知或混装输入给出具体差异并终止。已有 named 安装只接受清单中验证过的完整输入集。
2. **统一开发和发行的映射。** 现有开发构建消费 `starsector.named`；同级 NanoForge 的 `packFullMapping` 默认取 Paragon 投影的 `mappings-named.tiny`，两者来源和版本必须显式核对。先用拟发布清单生成编译用 named jar，再用同一清单产物编译 SSOptimizer，校验处理器、Mixin 指向的类、成员和描述符。每个平台输入集必须映射到这个共同 ABI。发行前核实映射和运行库的再分发许可；发布物不含商业游戏字节码、生成的 named jar 或用户模组副本。
3. **整组重映射。** 以全部游戏输入和依赖建立类层级，用一次 remap 处理跨 jar 的继承与接口关系，按来源分回各 jar 并保留资源；这与 Paragon 的 `mapNamedJar`/`mapNamedJars` 分工一致。核实名称、字段和方法描述符、继承声明和调用、签名、注解、`invokedynamic`、方法句柄、内部类与 nestmate 元数据。检查包级访问和跨 jar 引用，拒绝映射冲突、丢失条目或错误的帧。修改过的归档必须按清单处理失效签名和 manifest；保留必要资源、服务提供者和多版本目录语义。
4. **准备模组。** 按游戏实际启用列表与优先级读取 `mod_info.json`，只处理启用模组的 jar；保留顺序和资源覆盖规则。归档转换也要处理类名保持不变、但引用了 obf 成员的类。为每个来源标记 `obf`、已按指定 ABI 生成的 `named` 或本轮产生的 named 副本，禁止对 named 输入重复改写。普通历史模组按对应的原版 ABI 解析；同时引用多个命名空间或存在歧义时，要求显式声明并报告冲突。保留原模组目录与缓存 jar 的关联，区分读取游戏数据的路径与给类加载器使用的 named `CodeSource`。已知的类名字符串和带 owner、签名的成员查找可定向映射；无上下文的普通字符串不批量替换。动态拼出的混淆成员名应由可定位的调用点适配单独验证。
5. **发布缓存。** 在私有缓存目录下加写锁，将产物写到临时目录；校验完整清单和输出哈希后原子发布。游戏缓存键包含输入、映射及工具摘要。模组缓存另含模组字节、参与解析的有序依赖及来源命名空间。ASM/Mixin 在启动时执行，不把已织入的游戏类持久化为 named 基线。中断留下的临时文件不参与下次启动，旧版本缓存可随原启动配置继续使用。

## 游戏启动与类变换

建议 CLI 提供 `doctor`、`prepare`、`launch`。`launch` 自动完成检查和必要的准备；`doctor` 输出将使用的 JDK、游戏输入、映射、加载器依赖、native 和缺失项。Linux 使用游戏核心目录作为工作目录；Windows 显式区分游戏根目录和 `starsector-core`。入口选用经验证的 64 位 JDK 25，按平台建立 JVM 参数列表（模块开放、native access、游戏所需 native、存档、模组、日志及截图目录），用 `ProcessBuilder` 启动独立游戏 JVM 并返回其退出码。原始游戏资源仍从安装目录读取。

```text
独立入口 → prepare：本地游戏与启用模组 → named 归档和类路径清单
         → 游戏 JVM：RFB Main + RfbSystemClassLoader + Standalone ITweaker
         → 挂载 named 来源、建立委托规则、安装共享装配
         → Launch 域：named 字节 → HybridWeaver → GL 重定向 → Mixin → 定义类
         → com.fs.starfarer.StarfarerLauncher.main(args)
```

- **System 域。** 只放 bootstrap、宿主基础库与明确归此域的库。RFB 系统插件按现有注册表处理 LinuxDisplay、LinuxKeyboard、LinuxEvent 和 WindowsDisplay 的 IME 改写；必须在目标首次定义前就绪。系统域改写后访问的 SSOptimizer hook 必须经受控委托指向 Launch 中的同一实现。生成本地 LWJGL 去密封副本时只处理已知输入和冲突项，classpath 只使用一份 LWJGL 与匹配的原生库。JNI、日志绑定、`Global`、`ServiceRegistry`、GL bridge 与渲染队列均须核查其实际定义加载器。
- **Launch 域。** 挂载 named 游戏、游戏侧库、SSOptimizer 负载与全部启用模组，再允许 Mixin 首次 select/prepare。由独立 tweaker 引导 `MixinBootstrap`、MixinExtras 和 LaunchWrapper 的 Mixin tweaker/阶段切换，使实际执行顺序为共享 ASM、GL 重定向、Mixin；仅调用 `Mixins.addConfiguration` 不足以安装 transformer。复用现有 Mixin 配置、`shouldApplyMixin`、`ssoptimizer.disable.*` 以及 bridge/direct 变体。`getClassNode(name, false)` 应读到 named 的未织入字节，查询过程中不得提前定义目标类。两个宿主都核对实际 transformer 顺序和输出字节。
- **脚本和自建 loader。** `ScriptStore.createSourceClassLoader` 与启动清单共用解析结果，把用户模组的原路径对应到 named 副本；改动启用集合后重新启动以重新执行 Mixin prepare。Janino 在 named 类路径编译；直接读取 `CodeSource`、AITweaks、Ship Mastery 和缓存脚本的路径分别核实。需要在私有 loader 中定义的类，按具体 loader 的现有适配，在 `defineClass` 前为原始来源执行 remap 和该域指定的变换；已处理的字节跳过对应阶段，保留原 loader 的包访问和资源语义。在创建游戏脚本子加载器时，用识别受管理类的父加载器委托给 Launch。对已索引资源，Launch 抛出的 `ClassNotFoundException`（包括包装的变换异常）必须转为带原始 cause 的 `LinkageError`，记录类名、jar、加载器和阶段；否则子加载器会继续 `findClass`，使外部类和内部类由不同加载器定义。私有 loader 的目标类按该 loader 的归属表处理。
- **两种宿主的职责。** NanoForge 继续负责自己的 remap、可选 binpatch、coremod 排序和系统注册；SSOptimizer 不再对其输出执行第二次 remap。Standalone 接收缓存中的 named 字节，仅对尚未处理且来源明确的动态输入补做 remap。现有处理器、Mixin 注入点和禁用开关语义保持一致。Standalone 专用的模组挂载与类来源处理应使用独立宿主配置，禁止在 NanoForge 下再注册一遍。

## 实施顺序与验收

| 提交范围 | 完成条件 |
| --- | --- |
| 拆分构建和共享装配 | 先使 NanoForge 使用拆出的共享负载和入口；核对原包布局、两个 transformer 的注册和禁用开关，在原运行配置进入游戏。此时即可独立合并，不要求用户迁移。 |
| 固定映射 ABI，完成 `doctor`/`prepare` | 在干净的受支持安装生成 named 环境，原游戏哈希不变；校验跨 jar 继承、方法句柄与内部类，拒绝错误映射。冷启动、缓存命中、模组更新和准备过程被中断分别验证。 |
| 完成 Standalone 引导和宿主适配 | 在未安装 NanoForge 且没有开发仓库的环境，从自带入口进入标题界面；核实关键类定义域、实际 Mixin service、两条 ASM 入口、四个 IME 目标的适用平台和渲染开关变体。类变换异常要能定位到来源，禁止把进入标题界面作为全部验收。 |
| 发布两个发行版 | 在 Windows/Linux、NanoForge/Standalone、渲染线程开/关的组合上，从本地安装启动、读写测试存档并进入战斗。包含原版模组、Janino、AITweaks/Ship Mastery、LazyLib/HSI 并发场景、DCR 缺失/旧版/自带批量保存版。对照 DCR 主类与内部类的定义加载器、IME 输入、GL bridge 调用、JNI 和模组资源路径。核对独立包无游戏 jar、无 NanoForge 运行依赖；验证卸载后原入口和存档路径仍可用。 |

单元测试覆盖映射与层级解析、归档拆分、成员/调用点改写、模组顺序、缓存失效规则；类加载器、Mixin 生命周期、native 和打包部署通过真实发行包启动及针对性场景验证。建议首先交付 **NanoForge 上的共享装配拆分** 和 **用发布映射在本地生成可编译且可加载的 named 游戏环境**。这两项分别验证宿主边界与命名空间，是继续接入独立启动器的前提。
