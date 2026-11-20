# SSOptimizer 项目规范

## 模块地图（Gradle 多模块）

目录布局：`modules/api/`（跨域契约）、`modules/internal/`（实现模块）。

- `:modules:api:sso-api` — 跨域服务接口层，只放接口与契约 record，零实现、零第三方依赖。
- `:modules:internal:sso-core` — 共享基础设施：ASM/Mixin 装配（HybridWeaverTransformer）、
  日志、ServiceRegistry、NativeRuntime/NativeLibraryResolver、
  通用帧内并行执行器（common/concurrent/FrameParallelExecutor + 统一入口
  SharedFrameWorkers 共享池供 sso-ai 战斗 AI 使用，市场推进在战役线程按原顺序执行；
  IO 阻塞/一次性批任务走 VtWorkers 虚拟线程门面）、杂项引擎级优化。
- `:modules:internal:sso-app` — 装配模块：coremod 入口（SSOptimizerCorePlugin）、
  跨域服务注册、shade 打包。
- 功能模块：`:modules:internal:sso-render`（渲染线程）、`:modules:internal:sso-ai`（异步 AI）、
  `:modules:internal:sso-loading`（加载优化）、`:modules:internal:sso-font`（字体渲染）、
  `:modules:internal:sso-ime`（输入法）、`:modules:internal:sso-save`（存档读写）、
  `:modules:internal:sso-automation`（自动化与基准测试）、
  `:modules:internal:sso-debug`（调试服务：localhost HTTP RPC、动态脚本编译执行、热重载，
  默认关闭，`-Dssoptimizer.debug.enabled=true` 启用；设计见 docs/design/debug-capability-design.md）。
- 含 C++ 支持的模块下设 `<域>/native/` 子模块（native-render/native-loading/
  native-font/native-ime；sso-loading 域下另有 native-texcompress，BC1/BC3/BC7
  纹理压缩），产物 `libssoptimizer_<module>.so`，
  JNI 生成头统一落在根级 `native-headers/generated/`。

## 跨域调用：必须经 `:modules:api:sso-api` 接口

1. 功能模块只允许依赖 `:modules:api:sso-api` + `:modules:internal:sso-core`；
   功能模块之间**禁止直接依赖**
   （唯一既有例外：sso-font → sso-render 的动态图集纹理上传复用 GlDispatch）。
   `:modules:internal:sso-app` 是装配模块，依赖全部功能模块，不受此限制。
2. 跨域行为调用在 `:modules:api:sso-api` 定义接口，实现方模块提供实现，由
   `:modules:internal:sso-app` 的 `SSOptimizerCorePlugin.registerModuleServices()` 注册进
   core 的 `ServiceRegistry`；调用方经 `ServiceRegistry.require/getOrNull` 解析。
   语义允许缺省的接口必须在 javadoc 写明「未注册=何语义」，调用点显式判空。
3. native 库按模块拆分加载：主库 `render` 由 `NativeRuntime.ensureLoaded()` 加载，
   其余经 `NativeRuntime.loadModule("<module>")` 懒加载。

## 字节码改写：Mixin 优先，ASM 兜底

1. 对游戏类的行为改写（方法调用重定向、字段访问重定向、方法头/尾注入、Accessor）
   **必须使用 Mixin**（各功能模块的 `src/main/java/.../mixin/`，注册进 `mixins.ssoptimizer.json`）。
2. 仅当 Mixin 在技术上完全不可实现时才允许使用 ASM 处理器
   （`asm/` 包 + `HybridWeaverTransformer`），且必须在处理器 javadoc 中写明
   「为什么 Mixin 不可行」。已知必须走 ASM 的情形：
   - 需要跨指令的上下文匹配（如「NEW 之后紧跟特定 PUTFIELD」的序列改写）；
     但优先评估 Mixin 的 `@Redirect` FIELD/INVOKE + `@Shadow` 替代写法。
   - 类结构级修改（追加 implements 接口、新增字段/方法）。Mixin 的接口注入
     （mixin 类直接 implements 目标接口）可覆盖大部分场景，优先用 Mixin。
3. 禁止为同一目标同时注册 Mixin 与 ASM 处理器造成重复改写。
4. 执行顺序：ASM transformer（`HybridWeaverTransformer`、`RenderThreadRedirectTransformer`）先于 Mixin。
   渲染线程分离开启时，Mixin 看到的 GL 调用已被改写为 `bridge/opengl/GL11` 等 bridge 调用，关闭时仍是原始 LWJGL 调用。
   因此涉及 GL 调用的 Mixin 必须拆成 bridge 与 direct 两个变体，由 `SSOptimizerMixinConfigPlugin` 按 `RenderThreadMode.isEnabled()` 择一织入。
5. `ssoptimizer.disable.<key>` 开关：ASM 处理器在 `SSOptimizerCorePlugin` 注册时读取，Mixin 在 `SSOptimizerMixinConfigPlugin` 织入期读取。

## 类变换通道：禁止 javaagent 模式

1. **禁止以 javaagent（`java.lang.instrument` / `-javaagent:`）作为类变换通道**，
   包括注册 Instrumentation transformer、以及任何形式的「全类加载器兜底改写」。
   一切改写必须走 NanoForge 的 RFB/LaunchWrapper transformer 链 + Mixin。
2. 所有模组必须遵循同一套加载、重映射、渲染与部署规则。禁止按第三方模组的 ID、版本、包名、私有类或方法、文件名、目录布局或字节码指纹选择行为。禁止修改第三方模组的配置、日志阈值或存储格式。实际模组可用作外部回归样本。
3. 自建加载器的处理必须依据 JDK 类加载接口和定义域。保留原加载器、保护域与资源路径；只有定义域可访问所需依赖时才可改写。`Class.getName()` 及其方法引用必须保留 JVM 语义。兼容旧游戏符号时必须依据映射表和调用语义，禁止全局伪造类名。
4. **调试会话例外**：sso-debug 的 L2 hotswap（`HotswapSupport`）允许使用
   `Instrumentation.redefineClasses`，边界：
   - 仅限 sso-debug 模块、仅 `-Dssoptimizer.debug.enabled=true` 调试会话可触达；
   - 仅方法体级重定义（HotSpot 语义：不增删成员、不改签名），不注册 transformer、
     不产出持久类变换；
   - 自 attach 经 jdk.attach 运行期完成，禁止要求 `-javaagent:` 启动参数。

