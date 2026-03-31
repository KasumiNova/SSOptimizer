# Starsector 渲染热点引擎侧注入优化设计（分阶段激进版）

## 1. 背景与范围

基于 `热点.xml` 的方法级热点数据，渲染瓶颈集中在 LWJGL GL11 immediate mode 调用链：

- `GL11.nglVertex2f`（13.7%）
- `GL11.nglTexCoord2f`（11.0%）
- `GL11.nglTranslatef`（3.9%）
- `GL11.nglColor4ub`（3.8%）
- `GL11.nglFinish`（3.0%，来自 `CombatState.traverse`）

主要上层调用点：

- `com.fs.graphics.Sprite.render(FF)V`
- `com.fs.graphics.particle.GenericTextureParticle.render()V`
- `com.fs.starfarer.renderers.fx.DetailedSmokeParticle.render()V`
- `com.fs.starfarer.combat.entities.ContrailEngine.new(F)V`
- `com.fs.starfarer.combat.CombatState.traverse()Ljava/lang/String;`

**约束：仅做引擎侧注入改造，不修改 mod 代码。**

## 2. 目标与非目标

### 2.1 目标

1. 在不破坏视觉正确性的前提下，显著降低渲染 JNI 调用和 GL 状态切换开销。
2. 通过分阶段落地与 feature flag，实现可灰度、可回滚的演进。
3. 对战斗场景提供可重复的性能验证与图像一致性验证。

### 2.2 非目标

- 不改动第三方 mod 渲染逻辑。
- 不在首轮引入大规模 API 重构。

## 3. 设计总览（用户已选：D 分阶段推进）

### Phase A（低风险，快速收益）

- 在 `CombatState.traverse` 注入条件控制：默认旁路 `glFinish`，仅在 debug/profile 开关开启时允许执行。
- 在 `com.fs.graphics.Object.Ø00000()` 纹理绑定路径注入“重复 bind 去重”。

### Phase B（中风险，中高收益）

- 对以下方法注入状态缓存与 fast path：
  - `Sprite.render`
  - `Sprite.renderRegion`
  - `GenericTextureParticle.render`
  - `DetailedSmokeParticle.render`
- 目标：减少重复 `glBlendFunc` / `glBindTexture` / `glPushMatrix` 相关调用。

### Phase C（高风险，高收益）

- 注入统一 `QuadBatch` 渲染通道：按 `texture + blend mode` 分桶批处理。
- 将高频 immediate mode 热路径从“每对象 glBegin/glEnd”迁移到“批量 flush”。

## 4. 技术策略与注入点

### 4.1 注入机制

- 使用 Java Agent / 字节码变换（启动参数注入）对目标类方法进行切面式改造。
- 全部改造受 runtime feature flags 控制（可动态开关或重启切换）。

### 4.2 状态缓存（RenderStateCache）

维护最近状态：

- 绑定纹理 ID
- blend 函数组合
- 必要的矩阵/颜色状态标记

策略：

- 同值调用直接短路，不下发 GL JNI。
- 遇到未知外部状态污染时强制失效并重新同步。

### 4.3 批处理（QuadBatch）

- 输入：来自 `Sprite/Particle` 路径的 quad 顶点与 UV、颜色、纹理、混合模式。
- 过程：按 key 分桶积累；达到阈值或状态变化时 flush。
- 输出：降低 `glVertex2f/glTexCoord2f` JNI 调用频次和状态切换抖动。

## 5. 验收指标（激进版，均为目标值）

### Phase A

- `glFinish` 频次：默认模式降至 0/帧（非调试场景）。

### Phase B

- `glBindTexture/glBlendFunc/glPushMatrix` 总调用量下降 **35%~55%**。
- `Sprite/Particle` 热点占比显著回落且无可见渲染回归。

### Phase C

- `nglVertex2f/nglTexCoord2f` 调用量下降 **60%~80%**。
- 重负载战斗场景帧时间稳定，无新增崩溃趋势。

## 6. 风险矩阵与回滚

### 6.1 风险

- Phase A（低）：GPU 同步语义变化导致特定调试场景观测差异。
- Phase B（中）：状态泄漏、混合模式不匹配、局部视觉异常。
- Phase C（高）：透明排序与批处理边界错误，mod 兼容行为波动。

### 6.2 缓解与回滚

- 每阶段独立 feature flag。
- 图像基线对比（关键战斗帧）。
- 回放场景性能对比（固定种子/同配置）。
- 任一阶段出现视觉错误或稳定性回退，立即回滚到上一稳定阶段。

## 7. 实施顺序（MVP 到增强）

1. **M1 / Phase A**：先落地 `glFinish` 管控 + bind 去重（建立最小收益闭环）。
2. **M2 / Phase B**：扩展 Sprite/Particle 热路径 fast path 与状态缓存。
3. **M3 / Phase C**：引入 `QuadBatch`，灰度开启并逐步扩大覆盖。

## 8. 测试与验证

- 功能正确性：视觉回归截图比对、关键特效专项检查。
- 性能验证：同场景采样帧时间分位数、GL 调用计数埋点。
- 稳定性：长时战斗 soak test + 崩溃日志监测。

## 9. 开发环境基线（Mixin + ASM + JUnit5）

### 9.1 依赖与版本策略

- 注入框架：`org.spongepowered:mixin`
- 字节码工具：`org.ow2.asm:asm`（及 `asm-commons` / `asm-tree`）
- 测试框架：`JUnit 5`

说明：游戏原始 jar 仅作为 `compileOnly` 引用，避免将运行时资源耦合进优化工程。

### 9.2 工程组织与运行模式

- 注入逻辑与测试夹具分模块隔离（便于按 phase 独立迭代与回滚）。
- 运行时通过启动参数加载 agent，并由 mixin 配置与 feature flags 控制启用范围。
- 变换目标使用“类名 + 方法签名”匹配，确保混淆环境下定位稳定。

### 9.3 质量门与降级策略

- 字节码变换单测：校验目标方法匹配与指令替换结果。
- 启动冒烟测试：确认战斗渲染链可进入并稳定运行。
- 失败降级：任一注入失败时自动退回“无注入路径”，并输出结构化日志。

## 10. 反编译 Mapping 环境（渲染优先）

### 10.1 工作流

- 采用“先重命名后开发”的流程，但首轮仅覆盖渲染链路：
  - `com.fs.graphics.*`
  - `com.fs.graphics.particle.*`
  - `com.fs.starfarer.renderers.*`
  - `combat.entities.*`（渲染相关）

### 10.2 映射资产

- 维护 `obf -> named` 映射（类/方法/字段三级）。
- 每条映射绑定证据：热点节点、`javap` 片段、调用链路径。
- 产出“开发镜像 jar”用于阅读和定位；运行时注入仍以原始符号 + 签名为准。

### 10.3 迭代策略

- 渲染热点优先，非渲染包延后。
- 每轮性能迭代后补齐新增热点映射，形成长期可复用的语义库。

## 11. 交付物

- 引擎注入模块（可开关）
- 性能与视觉回归报告模板
- 分阶段发布与回滚手册
- 渲染链路映射资产（含证据索引）

---

本设计经对话确认：采用 **分阶段推进（A → B → C）+ 激进目标 + 强回滚保护 + 渲染优先映射流程**。