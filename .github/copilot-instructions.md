# SSOptimizer 开发规范

## 项目概述

SSOptimizer 是以 NanoForge coremod 形式装配的 Starsector 性能优化模组，通过 RFB/LaunchWrapper transformer 链与 Mixin 在运行时修改游戏行为。仓库采用 Gradle 多模块布局，包含 Java 功能模块和 C++ JNI 原生子模块。

## 技术栈

- Java 25 + Gradle 9.x
- NanoForge RFB/LaunchWrapper + SpongePowered Mixin
- ASM 9，仅用于 Mixin 无法实现的字节码转换
- C++ 20 JNI：OpenGL 批渲染、PNG 解码、BC1/BC3/BC7 纹理压缩、FreeType 字体栅格化、XIM/IMM32 输入法
- LWJGL 2（游戏引擎使用的 OpenGL 与输入绑定）

## 模块结构

```text
modules/
├── api/sso-api                 跨域接口与契约 record，零实现、零第三方依赖
└── internal/
    ├── sso-core                共享基础设施、ServiceRegistry、NativeRuntime、共享线程池
    ├── sso-app                 coremod 入口、服务注册、shade 与运行配置
    ├── sso-render              渲染线程、OpenGL bridge、渲染优化；含 native-render
    ├── sso-ai                  战斗 AI 异步并行与碰撞热路径优化
    ├── sso-loading             资源加载优化；含 native-loading 与 native-texcompress
    ├── sso-font                字体渲染与动态图集；含 native-font
    ├── sso-ime                 XIM/IMM32 输入法；含 native-ime
    ├── sso-save                存档读写优化与 saveBench 离线基准
    ├── sso-modopt              第三方模组专项优化
    ├── sso-automation          自动化、基准与启动器辅助
    └── sso-debug               默认关闭的 HTTP RPC、动态脚本与热重载调试能力
```

### 关键约束

- 功能模块只允许依赖 `:modules:api:sso-api` 与 `:modules:internal:sso-core`。唯一既有例外是 `sso-font` 依赖 `sso-render`，复用动态图集纹理上传的 `GlDispatch`。
- `sso-app` 是装配模块，依赖全部功能模块，不受功能模块依赖限制。
- 跨域行为必须在 `sso-api` 定义接口，由 `SSOptimizerCorePlugin.registerModuleServices()` 注册到 core 的 `ServiceRegistry`，调用方经 `ServiceRegistry.require/getOrNull` 解析。
- 各功能模块内，`asm/` 只放 ASM Processor，`mixin/` 只放 Mixin 与 Accessor，业务实现放在对应的 `common/` 领域包。注入代码与业务实现不得互相混放。
- 游戏类与成员的 named 名常量集中在 `sso-core` 的 `github.kasuminova.ssoptimizer.mapping` 包。

## 字节码改写策略

1. 方法调用或字段访问重定向、方法头尾注入、Accessor 等场景必须使用 Mixin。
2. 仅在 Mixin 技术上不可实现时使用 ASM，例如跨指令上下文匹配或 Mixin 接口注入无法覆盖的类结构修改。Processor 的类注释必须说明 Mixin 不可行的原因。
3. Processor 必须精确匹配目标类，并在 `SSOptimizerCorePlugin.registerAllProcessors()` 的注册链中装配。禁止为同一目标重复注册 Mixin 与 ASM 改写。
4. 禁止用 javaagent 或全类加载器兜底改写。System 域和模组自建类加载器使用针对性适配；仅 `sso-debug` 调试会话允许按 `AGENTS.md` 的边界使用 `Instrumentation.redefineClasses`。

## 注释规范

- `api` 包公开接口与核心模型必须写类头和公开方法简介，说明职责、调用前置条件、线程模型、返回值、异常语义与副作用。
- internal 实现、私有成员、常量与测试默认不写复述代码流程的注释，只在非直观并发边界、缓存失效条件、外部库缺陷绕过等歧义点保留单句说明。
- ASM Processor 与 Mixin 的类注释说明注入目标、动机和效果；ASM Processor 还需说明 Mixin 不可行的原因。
- JNI 边界注释说明对应 Java 方法、参数与返回值、内存所有权和释放责任。
- 注释统一使用简体中文，不写设计稿章节号、决议代号、版本演变或需求来源，不使用长破折号。

## 构建与测试

```bash
# Java 编译与单元测试
./gradlew test

# 全部原生模块
./gradlew \
  :modules:internal:sso-render:native-render:build \
  :modules:internal:sso-loading:native-loading:build \
  :modules:internal:sso-loading:native-texcompress:build \
  :modules:internal:sso-font:native-font:build \
  :modules:internal:sso-ime:native-ime:build

# 部署到游戏目录
./gradlew deployMod

# 烟测：启动器模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher

# 烟测：游戏模式
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game
```

- 改完代码必须运行 `./gradlew test`。
- 涉及字节码注入或原生代码改动时，必须运行烟测确认游戏能正常启动。
- 新增 ASM Processor 必须有字节码行为测试，不得只检查源码文本。

## 经验积累

- 开发过程中遇到通用且可重现的问题，要更新本文件或在 `.github/instructions/` 下补充对应领域的指令文件。
- 某个领域的最佳实践需要固化为工作流时，可创建 SKILL 文件。
