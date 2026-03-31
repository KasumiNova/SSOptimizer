---
description: "Java 开发规范：ASM Patch、Mixin 注入、Hook 方法、字节码转换、包结构"
applyTo: "app/src/main/java/**/*.java"
---

# Java 开发规范

## 字节码注入优先级

1. **Mixin 优先**：`@Inject`、`@Redirect`、`@Accessor`、`@Overwrite` 能解决的必须用 Mixin。
2. **ASM 兜底**：仅在以下场景使用 ASM：
   - Mixin 无法注入的混淆目标（如 private 方法、特定字节码模式匹配）
   - 需要在精确字节码位置插入逻辑（如在 `invokevirtual` 后立即注入）
   - Hook native 方法的调用点
3. 使用 ASM 时必须在 Processor 类注释中写明：**"为什么 Mixin 无法实现"**。

## ASM Processor 规范

- 继承 `AsmClassProcessor` 或 `CompositeAsmClassProcessor`。
- `isTargetClass(String name)` 必须精确匹配目标类名，不要用宽泛前缀。
- 在 `SSOptimizerAgent.createProcessors()` 中注册。
- 改动须有单元测试：构造目标类的 mock 字节码 → 运行 Processor → 验证输出字节码的行为。

## 包结构

- `asm/` 只放 ASM Processor（字节码转换类），不放业务逻辑。
- `mixin/` 只放 Mixin 类和 Accessor 接口。
- 业务逻辑一律放 `common/` 对应子包。
- Hook 方法类（如 `LinuxDisplayImeHooks`）放 `common/` 而非 `asm/`：它们是被注入代码调用的业务方法，不是注入逻辑本身。

## 命名约定

- ASM Processor：`*Processor.java`（如 `TextureObjectBindProcessor`）
- Mixin 类：`*Mixin.java`（如 `GEngineRenderMixin`）
- Accessor 接口：`*Accessor.java`（如 `GEngineAccessor`）
- Hook 方法类：`*Hooks.java` 或 `*Helper.java`
- 接口/桥接：`*Bridge.java`

## 注释

- 类级 Javadoc 必须说明职责。
- ASM Processor 的类注释必须包含：目标类、注入位置、注入动机、注入效果。
- Mixin 类的 `@Mixin(targets=...)` 之上加注释说明目的。
- 所有 public/protected 方法加 Javadoc。
- 语言：中文。
