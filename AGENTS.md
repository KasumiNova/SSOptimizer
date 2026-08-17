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
