---
description: "测试规范：单元测试、烟测、ASM 字节码验证、覆盖率"
applyTo: "app/src/test/**/*.java"
---

# 测试规范

## 必须测试的场景

1. **新增 ASM Processor**：构造最小化的目标类字节码 → 运行 Processor → 验证转换结果（调用了正确的 Hook 方法、插入了正确的字节码指令）。
2. **业务逻辑类**：纯逻辑测试，不依赖游戏运行时。
3. **JNI 桥接**：如果无法加载原生库，测试中 mock 原生调用或跳过（`@EnabledIf`）。

## ASM 字节码测试模式

```java
// 1. 用 ASM 生成目标类的 mock 字节码
ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
// ... 生成模拟游戏引擎类的字节码

// 2. 运行 Processor 转换
ClassReader cr = new ClassReader(cw.toByteArray());
ClassWriter cw2 = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
processor.createClassVisitor(cr, cw2);
cr.accept(cw2, 0);

// 3. 加载转换后的类，验证行为
Class<?> clazz = defineClass(cw2.toByteArray());
// 调用方法，验证 Hook 被触发
```

## 烟测

- 改动字节码注入或原生代码后必须烟测。
- 启动器模式：`./tools/smoke_test_game_launch.sh <gameDir> <timeout> launcher`
- 游戏模式：`./tools/smoke_test_game_launch.sh <gameDir> <timeout> game`
- 烟测不替代单元测试，两者互补。

## 运行测试

```bash
./gradlew test
```

## 测试位置

- 测试文件放在 `app/src/test/java/` 下，包结构镜像主代码。
- 测试资源放 `app/src/test/resources/`。
- JMH 基准测试放 `app/src/jmh/`。
