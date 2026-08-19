# SSOptimizer 项目规范

## 字节码改写：Mixin 优先，ASM 兜底

1. 对游戏类的行为改写（方法调用重定向、字段访问重定向、方法头/尾注入、Accessor）
   **必须使用 Mixin**（`app/src/main/java/.../mixin/`，注册进 `mixins.ssoptimizer.json`）。
2. 仅当 Mixin 在技术上完全不可实现时才允许使用 ASM 处理器
   （`asm/` 包 + `HybridWeaverTransformer`），且必须在处理器 javadoc 中写明
   「为什么 Mixin 不可行」。已知必须走 ASM 的情形：
   - 需要跨指令的上下文匹配（如「NEW 之后紧跟特定 PUTFIELD」的序列改写）；
     但优先评估 Mixin 的 `@Redirect` FIELD/INVOKE + `@Shadow` 替代写法。
   - 类结构级修改（追加 implements 接口、新增字段/方法）——Mixin 的接口注入
     （mixin 类直接 implements 目标接口）可覆盖大部分场景，优先用 Mixin。
3. 禁止为同一目标同时注册 Mixin 与 ASM 处理器造成重复改写。

## 类变换通道：禁止 javaagent 模式

1. **禁止以 javaagent（`java.lang.instrument` / `-javaagent:`）作为类变换通道**，
   包括注册 Instrumentation transformer、以及任何形式的「全类加载器兜底改写」。
   一切改写必须走 NanoForge 的 RFB/LaunchWrapper transformer 链 + Mixin。
2. 模组自建类加载器（如 aitweaks 的 Kotlin 引导 loader、shipmastery 的
   ReflectionEnabledClassLoader）漏 transform 的问题，**用针对性适配解决**：
   对该模组的 loader 类写专用 ASM processor（参照
   `asm/loading/AITweaksCoreLoaderProcessor` 模式），把其 defineClass 挂进
   Launch transformer 链；不得用 agent 做通用兜底。
3. 设计动机：agent 通道会让 System 域类（如 system classpath 上的 lwjgl）被改写出
   对 Launch 域 bridge 类的引用，定义方加载器不可见即运行期 NoClassDefFoundError；
   且「全覆盖兜底」与 NanoForge 的显式域模型冲突。针对性适配的改写范围显式可控、
   失败在启动期可见。

