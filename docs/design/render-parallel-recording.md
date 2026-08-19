# 渲染并行录制（分段录制 + 确定性合并）设计跟踪

状态：**阶段 1 落地中**（2026-08-19）。前置分析见 v50b profile 与会话决策；
阶段 0 审计产物见 `render-parallel-audit.md`（含 0.98a 情报修正：无 LazyFont，
字体链走 BitmapFontRenderer + GLListManager）；当前 RT tip 基线重测 avgFps 71.82
（120s gl_benchmark，替代 v50b 的 72.45 作为阶段 2 验收基线）。

## 背景数据（v50b，avgFps 72.45，主线程 49,091 wall 样本）

- 主线程分段：render 42.0% / advance 44.8% / other 13.1%
- render 段内可并行面：Ship.render 5,242 + encodeGroup 3,014 + flushVertexStream 3,522 + 粒子/尾迹 ≈ render 段 60-70%
- 串行保留面：GraphicsLib ShaderHook 4,926（全局 pass，无法按实体切开）
- 回放侧约束：渲染线程 frame-replay 已 63.8% 忙，VertexStream 消费占回放 43.9%——
  并行录制把主线程 render 压到 ~15-18% 后，回放侧预计在 ~90 FPS 接棒成为瓶颈

## 决策记录（2026-08-18，用户拍板）

1. **模组 hook 固定编排**：GraphicsLib / BoxUtil 等第三方 renderHook 不参与并行分区，
   在分段编排中作为**固定主线程串行段**（位置按原版调用序锚定），并行段不得跨越其边界。
2. **渲染显存（回放侧 VertexStream 消费）延后一轮**：并行录制落地并稳定后再处理回放侧瓶颈。
3. **字体渲染重做（仅记录，不实施）**：后续版本重做字体渲染以引入现代渲染管线
   （脱离 immediate/显示列表体系，SDF 或实例化字形），本轮不动。

## 设计骨架（候选）

- 按固定顺序分区（layer → 实体 range 两级），worker 各自录制到独立帧段缓冲
  （各自 RecordingContext / VertexStream / 命令段，基础设施已具备：ThreadLocal 上下文、
  并发安全池、commitSeq 序号）
- 主线程按分区序拼接段缓冲进帧命令列表；回放侧完全不动
- StateDedup 在段边界失效重置（保守）
- 复用 AI Worker 池（advance/render 两阶段不重叠，无 oversubscription）

## 主要风险

1. 渲染路径隐藏共享可变状态（LazyFont / SpriteBatch / GraphicsLib 静态缓存）——
   实施前必须先做**共享状态审计**（只读调查，产出可并行/必须串行调用点清单）
2. 段粒度太细得不偿失（拼接+调度开销）
3. 模组在实体渲染间插入自己的 GL 调用（renderHook 之外）——审计阶段需覆盖

## 下一步

- [x] 渲染路径共享状态审计（实施前置门禁）——`render-parallel-audit.md`，待用户过目
- [ ] 并行录制实现 goal（待审计结论后立项）——阶段 1 分段基础设施已实现
  （RenderSegment/RenderFrame 段化/BridgeSupport 段绑定与阻塞护栏），阶段 2 待门禁放行

## 实现计划（2026-08-18 定稿）

### 阶段 0：渲染路径共享状态审计（实施前置门禁，只读调查）

产出「可并行 / 必须串行」调用点清单。审计范围与方法：

- **静态审计**（named 源码 + javap）：render 段全链路（CombatEngine.render →
  LayeredRenderer → Ship.render / Engine / 粒子组 / 尾迹 / 弹道 / HUD）中所有
  写操作落点——静态字段、跨实体容器、单例缓存。判定规则：只读游戏状态 = 可并行；
  任何写或读写混合（含惰性初始化缓存）= 必须串行或加守卫。
- **重点已知名单**：LazyFont（SpriteBatch 每帧新建）、SpriteBatch 内部状态、
  GraphicsLib 静态逐帧缓存（TextureData/LightShader 的 String 查表缓存）、
  BoxUtil/模组 renderHook、MagicLib trails、战斗脚本插件的 render 回调。
- **产出**：`render-parallel-audit.md` 清单（调用点 → 判定 → 依据），
  以及分区方案初稿（哪些 layer/实体组进并行段，锚定哪些串行段）。

### 阶段 1：分段基础设施

- RenderFrame 支持**有序段**：N 个段缓冲 + 按段序拼接进命令列表（拼接即 buffer
  拷贝，O(总命令数)）；FramePool 预热扩展为段缓冲预热。
- RecordingContext 段模式：worker 各自持有独立上下文（ThreadLocal 已具备），
  段内 commitSeq 局部化，拼接时重排为全局序（StateDedup 在段边界失效重置，保守）。
- 执行器复用：advance 与 render 不重叠，复用 AI Worker 池承载渲染分段任务
  （或泛化为阶段执行器，按审计结论定）。
- 门禁：单测（段拼接顺序、dedup 边界失效、池并发）+ `:app:test --rerun-tasks`。

### 阶段 2：实体级并行录制

- 分区：按 layer → 实体索引 range 两级；Ship/Engine/粒子/尾迹进并行段。
- **模组 hook 固定编排**（用户决策 1）：GraphicsLib ShaderHook、BoxUtil 等
  renderHook 作为固定主线程串行段，位置按原版调用序锚定，并行段不跨越其边界。
- 开关：`-Dssoptimizer.render.parallel=false` 整体回退（历史惯例）。
- 门禁：smoke launch（mixin 改动新规）+ 基准 PASSED 不降 + **逐帧截图对比**
  （并行顺序错误的典型症状 = 贴图/混合错乱/尾迹串层，需多帧确认）。

**实施记录（2026-08-19）**：

- 前置改造三项全部落地：静态能力缓存（GL11 bridge，commit 0d97234 + 2e18deb）、
  H2 复核排除（写入全在串行 advance 阶段，无需改造，commit d6e462b）、
  H1 GLListManager 并行安全化（DisplayListGuard 整体接管 + 5 方法 @Overwrite
  + 5 类字段读写 @Redirect，commit d2caa2d）。
- 编排器落地（commit c7dd42e）：@Redirect 单点拦截 renderExcluding →
  ParallelLayerRenderer 层内分片并行（舰载机-母舰同段经 LaunchingShipLink 接口
  注入；CustomCombatEntity 钉层尾串行段；worker 异常 fail-fast 传播）。
- BoxUtil：检测到即自动回退串行（接入方案见 boxutil-parallel-integration.md
  方案 A，列为独立增量）；四路回退均有一次性诊断日志。
- 全模组（97，含 BoxUtil）120s 基准：avgFps 68.86（回退路径，基线 71.82±3 内）。
- 注意：named jar 的 Ship 等游戏类含 JVM 规范外字段名（反混淆产物），
  单测环境不可加载——编排器与单测一律面向我方接口编程，不直接引用游戏类型。

### 阶段 3（顺延项，本阶段不做）

- 回放侧 VertexStream 消费优化（用户决策 2：渲染显存方向延后一轮，
  待并行录制落地稳定后立项）。
- 字体渲染重做引入现代渲染管线（用户决策 3：仅记录，后续版本单独立项）。

### 总验收

1. 审计清单 + 各阶段 commit 合入 feat/render-thread；
2. 基准 PASSED 且 avgFps 不低于立项基线（±3 噪声带），预期目标 85+ FPS；
3. 三项错误 0、AI 异常 0、MixinApplyError 0（smoke + 基准双门禁）；
4. 逐帧截图渲染正确；5. 推送并 ls-remote 确认。

## 已知问题（跟踪）

### 极度偶发画面撕裂（2026-08-18 记录，未立项）

现象：实战中极度偶发地出现一帧画面上下两半内容不一致（截图实证：上半为三舰编队
某一帧、下半为另一帧，星野与舰船位置对不上，红色武器射程线横跨两帧），疑似
「一帧内混入两帧」的呈现级撕裂。

方向假设（按嫌疑排序）：

1. **帧边界竞态**：渲染线程回放帧 N 的命令流时，主线程已开始录制帧 N+1，
   若某个共享缓冲（VertexStream 借出缓冲 / 状态快照）在「渲染线程尚未读完」时
   被主线程覆写，呈现帧即为两帧混合。重点排查悬挂续跑（continuation）与
   缓冲归还时机——归还信号是否以「命令执行完」而非「swap 完成」为准。
2. **swap 与回放的交错**：Display.update 内 swapBuffers 与命令回放同线程串行，
   但若某路径（模组 hook / FBO pass）绕过队列直接执行 GL，会与回放交错。
3. **vsync/呈现层**：排除项——原版同设置下无此现象，且截图（back buffer 内容）
   本身已含撕裂，说明混合发生在呈现前的帧内容生产阶段，非显示器刷新撕裂。

取证手段：Display 调试帧抓取钩子（-Dssoptimizer.debug.framecapture.dir/.frame）
可在战斗中连续抓帧复现后比对相邻帧。发生频率极低，暂不阻塞主线，待并行录制
（阶段 2）落地时统一复核帧边界不变量。
