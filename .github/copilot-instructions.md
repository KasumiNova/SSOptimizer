# SSOptimizer 开发规范

## 项目概述

Starsector 游戏性能优化 Java Agent，通过字节码注入（ASM / Mixin）在运行时修改游戏引擎行为。包含 Java 主模块和 C++ JNI 原生模块。

## 技术栈

- Java 25 + Gradle 9.x
- C++ 20（JNI 原生模块：字体栅格化、PNG 解码、OpenGL 批渲染、Linux IME）
- SpongePowered Mixin（通过 javaagent 桥接）
- ASM 9（字节码转换）
- LWJGL 2（游戏引擎使用的 OpenGL / 输入绑定）

## 包架构约定

```
github.kasuminova.ssoptimizer/
├── bootstrap/      # javaagent 入口，ASM / Mixin 字节码框架初始化
├── asm/            # ASM 字节码 Patch（仅放 Patch 和 Hook，不准放业务逻辑）
│   └── ime/        # 输入法相关 ASM Patch
├── mixin/          # Mixin Patch（仅放 Patch 和 Hook，不准放业务逻辑）
│   ├── accessor/   # Mixin Accessor 接口
│   ├── render/     # 渲染相关 Mixin
│   └── service/    # Mixin 服务引导
├── common/         # 主功能实现
│   ├── combat/     # 战斗系统优化
│   ├── font/       # 字体渲染 + 高 DPI 适配
│   ├── input/ime/  # 输入法服务逻辑
│   ├── launcher/   # 启动器优化
│   ├── loading/    # 资源加载性能优化
│   ├── logging/    # 日志降噪
│   └── render/     # 渲染管线优化
└── (根包)          # App.java, SSOptimizerModPlugin.java
```

### 关键约束

- `asm/` 和 `mixin/`：**只放注入代码和 Hook 方法**，不准放业务逻辑。业务逻辑放 `common/` 对应子包。
- `common/` 下按**功能领域**拆分子包，一个包只做一件事，不准混放多个业务内容。
- 不要蜘蛛网架构：模块之间低耦合，禁止循环依赖。
- Patch 类和业务逻辑类分离：Patch 调用 Hook/业务类，业务类不依赖 Patch 类。

## 字节码注入策略

1. **优先使用 Mixin**：能用 `@Inject`、`@Redirect`、`@Accessor` 解决的场景必须用 Mixin。
2. **ASM 仅在 Mixin 不可达时使用**：例如需要修改混淆类的 private 方法、hook native 方法调用点、或需要精确控制字节码插入位置时。使用 ASM 的 Processor 必须在类注释中写明**为什么 Mixin 无法达成目的**。
3. Processor 的 `isTargetClass()` 要精确匹配，不要用宽泛的包名前缀匹配。

## 注释规范

- **所有类必须有类级 Javadoc**，说明该类的职责。
- **所有 public/protected 方法必须有 Javadoc**，说明参数、返回值、副作用。
- **字节码注入类（ASM Processor / Mixin）必须在类注释中**写明：注入目标、注入动机（为什么需要这个 Patch）、注入效果。
- **C++ 代码同样要求注释**：JNI 函数说明对应的 Java 方法、参数含义、内存管理策略。
- 注释语言统一使用**中文**。

## 构建与测试

```bash
# Java 编译 + 单元测试
./gradlew test

# Native 编译（需要 FreeType + C++ 工具链）
./gradlew :native:build

# 部署到游戏目录
./gradlew installDevMod

# 烟测（启动器模式）
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> launcher

# 烟测（游戏模式）
./tools/smoke_test_game_launch.sh <gameDir> <timeoutSec> game
```

- **改完代码必须跑单元测试**（`./gradlew test`）。
- 涉及字节码注入或原生代码改动时，必须跑一次**烟测**确认游戏能正常启动。
- 新增 ASM Processor 必须有对应的单元测试，验证字节码转换结果。

## 经验积累

- 开发过程中遇到通用的、可重现的坑，要更新本提示词文件或在 `.github/instructions/` 下创建新的 instructions 文件，避免反复踩坑。
- 如果某个领域的最佳实践值得固化为工作流（如"Linux IME 调试流程"），可创建 SKILL 文件。

## 经验积累

- 开发过程中遇到通用的、可重现的坑，要更新本提示词文件或在 `.github/instructions/` 下创建新的 instructions 文件，避免反复踩坑。
- 如果某个领域的最佳实践值得固化为工作流（如"Linux IME 调试流程"），可创建 SKILL 文件。
