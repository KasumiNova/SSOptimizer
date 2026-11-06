---
description: "测试规范：单元测试、烟测、ASM 字节码行为验证"
applyTo: "modules/**/src/test/**/*.java"
---

# 测试规范

## 必须测试的场景

1. **新增 ASM Processor**：使用最小字节码夹具或真实 named jar 执行转换，验证目标调用、控制流和转换后行为。禁止只做源码文本包含检查。
2. **业务逻辑**：测试可观察行为与边界条件，不依赖游戏运行时，不照搬实现步骤，不断言易调配置值。
3. **JNI 桥接**：通过现有桥接替身隔离原生库；确实依赖原生环境时使用 `@EnabledIf` 明确运行条件，不得静默跳过失败。

## ASM 字节码测试

- 使用 ASM 生成只包含目标结构的最小类，或加载 SourceSector named jar 中的真实类。
- 将原始字节码交给待测 Processor，检查转换后的调用 owner、方法描述符、指令顺序与必要的控制流。
- 需要验证运行行为时，通过项目现有测试类加载设施加载转换结果，不得使用反射绕过封装。
- `RealBytecodeIntegrationTest` 负责真实 named jar 的集成验证，相关 Processor 改动必须保持其通过。

## 烟测

- 改动字节码注入或原生代码后必须烟测。
- 启动器模式：`./tools/smoke_test_game_launch.sh <gameDir> <timeout> launcher`
- 游戏模式：`./tools/smoke_test_game_launch.sh <gameDir> <timeout> game`
- 烟测不替代单元测试。

## 运行测试

```bash
./gradlew test
```

## 测试与基准位置

- 单元测试放在对应模块的 `src/test/java/`，包结构镜像主代码；测试资源放在该模块的 `src/test/resources/`。
- JMH 基准位于 `modules/internal/sso-app/src/jmh/java/`，经 `:modules:internal:sso-app:jmh` 运行。
- 存档离线基准位于 `modules/internal/sso-save/src/saveBench/java/`，经 `:modules:internal:sso-save:saveBench` 运行。
