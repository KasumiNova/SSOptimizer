# SSOptimizer 调试能力扩展设计（Debug Server / 动态脚本 / MCP）

> 状态：P1–P4 已实现并验证（2026-09-07）。P5（MCP / Kotlin）延期。
> 实测修正记录：
> - 脚本编译器获取：RFB 自定义系统类加载器下 `ToolProvider.getSystemJavaCompiler()`
>   返回 null，须回退 `ServiceLoader.load(ModuleLayer.boot(), JavaCompiler.class)`
>   （jdk.compiler 提供者声明在 module-info，类加载器路径 ServiceLoader 不可见）；
> - 主线程排空锚点：`AppDriver.begin` 只调一次、`BaseGameState.traverse` 只在状态
>   切换间调一次（帧 while 循环在 traverse 内部），均不可用；实际锚点为 traverse
>   循环体内的 `SoundManager.advance(FFFFFI)V` 调用点（空转分支亦每迭代必达）；
> - 自 attach：`jdk.attach.allowAttachSelf` 在 zulu25 启动期读取，运行期
>   setProperty 无效，hotswap 需要启动参数携带该属性（非 -javaagent）；
>   RFB 系统类加载器下 loadAgent 实测可用。

## 1. 背景与目标

当前实机验收只有「重启游戏 + automation 场景」一条通道，迭代周期以分钟计。
Agent 调试场景需要秒级反馈：改一段逻辑 → 立刻在游戏内执行 → 读回状态。
本设计在 SSOptimizer 内建一套**默认关闭**的调试服务端，首轮对外提供：

1. 动态脚本编译执行接口（Java 初版，Kotlin 后续）——秒级行为验证；
2. JVM 类热重载调试接口——L1 脚本类加载器重载（首轮）+ L2 方法体 hotswap（次轮）；
3. HTTP RPC 接口 + 两份 Agent SKILL 使用文档（script-debug / hotreload）；
4. MCP 服务器——**延期**，本文仅记录预期设计（见 §3.5）。

## 2. 可行性依据（已核实的环境事实）

| 事实 | 来源 | 结论 |
| --- | --- | --- |
| 游戏运行在 zulu25 **完整 JDK**（非 JRE） | `zulu25_linux/bin/javac`、`jmods/jdk.compiler.jmod`、`jdk.attach.jmod`、`jdk.httpserver.jmod` 均存在 | `javax.tools` 编译、自 attach、内嵌 HTTP 服务全部可行，零新增运行时要求 |
| 游戏 classpath 自带 `janino.jar` + `commons-compiler-jdk.jar` | 游戏根目录 jar 清单 | 备选轻量编译器；初版不依赖（JDK compiler 更完整） |
| sso-core 已有 `VtWorkers` 虚拟线程门面、`ServiceRegistry` 跨域服务 | `common/concurrent/VtWorkers` | HTTP 服务线程模型、跨模块服务暴露均有现成模式 |
| sso-app 已有 shade 依赖通道（fastutil/jctools/zstd-jni） | `sso-app/build.gradle.kts` | Gson（约 300KB、零传递依赖）可直接 shade 进 coremod jar |
| automation 模块已有「系统属性配置 + Mixin 入口」范式 | `AutomationConfig`、`TitleScreenAutomationMixin` | DebugConfig 沿用同一范式 |
| JDK 自 attach 无需启动参数：`jdk.attach.allowAttachSelf` 是运行期读取的系统属性，`System.setProperty` 后置即可attach 自身 | jdk.attach 实现（ByteBuddy 等同款路径） | L2 hotswap 不需要 `-javaagent` 启动参数，满足纳入条件 |

## 3. 架构决策

### 3.1 模块划分：新设 `:modules:internal:sso-debug`

不复用 sso-automation。两者生命周期与受众不同：

- sso-automation：一次性验收流程（烟测/基准），跑完即终，是「唯一实机验收通道」，保持纯净；
- sso-debug：常驻 localhost 服务，面向交互式 Agent 调试，默认关闭
  （`-Dssoptimizer.debug.enabled=true` 才启动）。

sso-debug 只依赖 sso-api + sso-core，符合模块地图约束；对游戏状态的读取经
sso-api 接口与编译期 named jar 类型完成，不反向依赖功能模块。

### 3.2 传输层：jdk.httpserver + Gson

- 单端口 HTTP（默认 127.0.0.1:8471，可配），JSON-RPC 2.0 风格单端点 `/rpc`；
- **JSON 序列化使用 Gson**：现代 API、注解式字段映射、与 Java record 兼容好；
  经 sso-app 既有 shade 通道打进 coremod jar（约 300KB、零传递依赖），
  不使用游戏 classpath 自带的 org.json（API 陈旧、无类型映射）；
- 认证：启动时生成随机 token 写入 `<outputDir>/debug-token`，请求头
  `Authorization: Bearer` 校验；只绑回环地址；
- 服务端线程：`VtWorkers` 虚拟线程执行处理器，阻塞 IO 不进主线程/渲染线程。

### 3.3 动态脚本引擎（核心原语，同时是 L1 热重载）

- **编译**：`javax.tools.JavaCompiler`（jdk.compiler），`--release` 对齐 25，
  classpath 拼 LaunchClassLoader 可见的全部 jar（游戏 named jar + coremod 自身）；
- **隔离**：每次编译产出落入新的 `URLClassLoader`（父加载器 = sso-debug 模块加载器），
  句柄化注册（`scriptId → {classLoader, instance, version}`）；
- **重载语义**：同名 scriptId 再编译即新建 loader 与实例替换旧句柄，旧 loader 随引用
  解除被 GC——这就是脚本级热重载，完全自包含，不触碰任何已加载游戏类；
- **脚本契约**：实现 `DebugScript` 接口（sso-api 暴露），
  `Object invoke(DebugContext ctx, Map<String,Object> args)`；
  `DebugContext` 提供游戏入口句柄（CombatEngine/CampaignEngine 当前实例、
  Global 门面、日志、主线程调度器）；
- **执行线程选项**（`threadMode` 参数，RPC 与 API 双侧暴露）：
  - `new`（**默认**）：脚本在专用新线程执行，不阻塞游戏主循环；适合只读采样、
    日志dump、遥测分析等无副作用场景；
  - `main`：投递到游戏主线程下一帧执行，脚本与游戏逻辑串行，**用于规避业务
    状态的竞态**（读写实体、修改战役状态等必须与主循环互斥的操作）；
  - 脚本内禁止自行起线程操作 GL；渲染态操作由主线程模式下的 GL 段保证。
- **Kotlin（后续）**：`kotlin-compiler-embeddable` 约 80MB，shade 进 coremod jar
  不可接受。方案：编译器作为独立可选 jar 置于 `mods/ssoptimizer/debug/`，
  首次编译 Kt 脚本时按需 URLClassLoader 加载；未放置则 Kt 端点返回明确错误。

### 3.4 JVM 类热重载分级

| 级别 | 能力 | 机制 | 计划 |
| --- | --- | --- | --- |
| L1 脚本类重载 | 编译→替换脚本实例 | §3.3 类加载器隔离 | 首轮交付 |
| L2 方法体 hotswap | 已加载类的方法体替换（HotSpot 限制：不可增删成员/改签名） | 运行期自 attach：`System.setProperty("jdk.attach.allowAttachSelf","true")` → 生成微型 agent jar 至临时目录 → `VirtualMachine.loadAgent` → `Instrumentation.redefineClasses` | 次轮交付 |
| L3 全形态重载 | 增删方法/字段 | DCEVM / JBR 增强 hotswap，需换运行时 | 不做 |

L2 已确认**不需要 `-javaagent` 启动参数**（自 attach 全程运行期完成），
满足纳入条件。与 AGENTS.md「禁止 javaagent 类变换通道」的关系：该条款针对
游戏类持久改写（必须走 RFB/LaunchWrapper + Mixin），L2 是调试会话内的方法体级
临时替换，不产出持久变换、不改类结构。实施 L2 时在 AGENTS.md 增补
「调试会话 hotswap」例外条款，明确边界：仅限 sso-debug 模块、仅方法体重定义、
仅调试模式启用。

### 3.5 MCP 服务器（延期，仅记录预期）

首轮不实现，后续按此预期补建：

- 协议：MCP 2025-03-26 Streamable HTTP，`POST /mcp`（JSON-RPC）+ `GET /mcp`（SSE 通知流）；
- 方法子集：`initialize`、`tools/list`、`tools/call`、`resources/list`、`resources/read`、`ping`；
- tools 预期：`script_compile`、`script_invoke`、`script_list`、`hotswap`、
  `game_state_query`、`telemetry_query`（复用 automation 遥测）、
  `screenshot`（复用 FramebufferCapture）；
- resources 预期：调试日志尾部、automation 遥测 JSON、脚本句柄清单；
- 不做：prompts、sampling、elicitation、OAuth（回环 + token 已足）；
- 与 HTTP RPC 的关系：MCP 端点复用同一服务内核，仅加协议适配层。

### 3.6 Agent SKILL 两份（项目级 `.kimi-code/skills/`）

- `workflow-ssoptimizer-script-debug`：script 编译/调用/重载的调用约定、
  threadMode 选择准则（默认 new；改游戏状态必须 main）、DebugContext 可用
  句柄清单、常见配方（打印实体状态、临时 patch 一个判定、截图对比）；
- `workflow-ssoptimizer-hotreload`：L1 重载流程、L2 hotswap 的限制清单
  （仅方法体/不可改签名/已建实例字段不变）与失败判读。

SKILL 内命令走 `curl` 直连 HTTP RPC 端点，不依赖任何 MCP 客户端能力。

## 4. 分期实现计划

| 期 | 内容 | 验收 |
| --- | --- | --- |
| P1 骨架 | sso-debug 模块、DebugConfig（enabled/port/token）、Gson shade 接线、HTTP 服务、`ping`/`version` RPC、注册进 settings.gradle.kts 与 sso-app 装配、AGENTS.md 模块地图更新 | 单元测试（配置解析/端点鉴权/Gson 编解码）+ smoke 启动带 `-Dssoptimizer.debug.enabled=true` 后 curl ping 通 |
| P2 脚本引擎 | Java 编译管线、句柄注册表、L1 重载、threadMode（new 默认/main）、`script_compile`/`script_invoke`/`script_list` RPC | 单元测试（编译→调用→重编译→新行为生效；main 模式与主线程串行断言）+ 游戏内脚本读回 FPS |
| P3 SKILL | 两份 SKILL 落 `.kimi-code/skills/` | 按 SKILL 配方完成一次实机脚本调试 |
| P4 L2 hotswap | 自 attach + redefineClasses、`hotswap` RPC、AGENTS.md 例外条款 | 单元测试（方法体重定义后新行为生效）+ 游戏内热替换一处调试方法体验证 |
| P5（后续） | MCP 服务器（§3.5）/ Kotlin 编译器按需加载 | 视排期 |

## 5. 风险与开放问题

1. **脚本 classpath 完整性**：LaunchClassLoader 的 jar 清单需运行时枚举
   （`java.class.path` 在 RFB 体系下不全），P2 需实测编译可见性，缺口用
   模块自身编译期 classpath 显式补齐；
2. **自 attach 的 JDK 版本敏感性**：`jdk.attach.allowAttachSelf` 运行期
   setProperty 的路径在 JDK 9+ 各发行版行为一致（ByteBuddy 依赖此路径多年），
   但属未标准化行为，P4 开工先在 zulu25 上做一次最小验证；
3. **GL 线程安全**：脚本在主线程模式下可触碰渲染状态，调试接口本身是
   「受信任的破坏力」，不做沙箱；SKILL 文档明示仅限本地调试；
4. **端口冲突**：默认端口被占时递增重试 8471–8480，全失败则启动期报错关闭
   （不留静默兜底）；
5. **Kotlin 编译器分发**：80MB 可选 jar 的分发渠道未定（随 release 附件 / 用户自放）。
