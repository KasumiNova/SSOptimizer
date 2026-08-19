# 渲染/逻辑线程分离可行性结论（首轮调查）

本文档记录 SSOptimizer 第二阶段「渲染与逻辑分离」的代码级调查结论。
调查对象为 named 反编译源码（`com.fs.starfarer.*`）与 `starfarer_obf.jar` 字节码。
**结论：初版「advance/render 双线程 + 帧屏障」直接实施不可行（硬阻塞 5 项，均有代码证据）；
可行路径是按子系统渐进式队列化。** 本阶段以此文档作为该方向的初版交付。

## 主循环现状

- `AppDriver.begin()`（AppDriver.java:30-49）是主线程顺序状态机；Display 与 GL 上下文
  均创建/绑定于主线程（CombatMain.java:169）。
- `CombatState.traverse()`（CombatState.java:552-1535）每帧顺序：相机数学 →
  **渲染段**（752-1002：`engine.renderBG`/`engine.render`/UI）→ 输入收集 → UI advance →
  **引擎 advance**（1143，`combatStepsPerFrame` 循环）→ 输入处理（1158-1440，直接写引擎状态）→
  `glFlush/glFinish/Display.update` 屏障（1473-1487）→ 帧末实测步长供下一帧。
- 渲染天然滞后一帧（渲染上一帧 advance 完的状态），但 render、输入、UI、advance 在
  traverse 内交织，共享 `targetReticleRenderer`/`arcRenderer`/`viewport` 等对象，
  巨型方法本身无法干净切成两个线程。

## 硬阻塞清单（代码证据）

### 1. render 路径不是只读的

render 对实体/渲染对象做「写 → GL → 恢复」式裸字段写（无锁无 volatile），与并行
advance 必然数据竞争：

- `Ship.render`（Ship.java:2923-2935）：写 `visible/facing/location/extraAlphaMult` 后恢复；
  主 render 体（3378/3453/3512-3514）写 `visible/combinedAlphaMult/sprite(alpha,size,angle)`。
- `ShipDamageDecal.render`（ShipDamageDecal.java:128-143）：写共享 quad 的 alpha/color；
  而 brightness/flicker 由 advance 写（111-126）——同一对象双向写。
- `NebulaParticle.render`（NebulaParticle.java:104）写 `fullyFadedIn`，size 由 advance 写。
- `CombatEngine.renderBG`（CombatEngine.java:905/916）写 viewport 后恢复。

### 2. advance 路径间接执行 GL 调用

LWJGL 2 的 GL 上下文线程绑定（全工程无 `makeContextCurrent` 迁移），advance 离线程即失败：

- 新船部署：`advanceInner → executeAdds → addObjectToEngine → Ship.init →
  SpriteBatch.allocateBuffers`（SpriteBatch.java:85-98，`glGenBuffersARB/glBufferDataARB`）。
- 残骸清理：`advance → cleanUpShipsInHolo → Ship.cleanup → SpriteBatch.destroy`
  （383-387，`glDeleteBuffersARB`）。
- `GLListManager` 全局静态（`buildingList` 嵌套即抛异常），不支持并发建 list。

### 3. 实体容器并发必然 CME

- `ObjectRepository.getList`（ObjectRepository.java:63-71）直接返回内部 ArrayList 本体，
  add/remove 无任何同步；`executeAdds/Removes` 在 advanceInner 内每帧最多调用 3 次
  （CombatEngine.java:1226-1227/1506-1507/1519-1520），render 侧 for-each 同一列表。
- `LayeredRenderer`（LayeredRenderer.java:20-65）、`DynamicParticleGroup`
  （DynamicParticleGroup.java:33-71）、`FloatingTextManager`（FloatingTextManager.java:14-76）
  同样「advance 结构改、render 迭代」双向冲突。

### 4. AI/advance 间接结构性修改

AI 不直接调 GL，但通过引擎 API 触发 `addObject/spawnProjectile/粒子添加`
（ProjectileFactory.java:100-255 → CombatEngine.addObject 入队），结构性修改集中在
advance——这正是本阶段并行 AI 的前提：AI 产出的实体增删仍在主线程屏障后统一执行。

### 5. traverse 内输入处理直接写引擎状态

输入段（CombatState.java:1158-1440）直接 `engine.setPaused`（1349）、
`setTargetShip`（1264）等，render 段亦调用 `targetReticleRenderer.addReticle`
（863/872/899）——渲染器对象跨段共享，切线程需先拆解这些交织点。

## 可行的渐进路线（后续轮次候选）

按隔离度从高到低排序，每步独立验证：

1. **粒子层快照化**（推荐首切）：`DynamicParticleGroup` 容器改双缓冲/快照迭代，
   渲染线程异步执行粒子上传绘制；需先把 `NebulaParticle.render` 的 `fullyFadedIn`
   写挪进 advance 或随命令快照。粒子不参与碰撞、不读其他实体，隔离度最高。
   （本阶段粒子渲染已批量化，见 combat-render-logic-optimization.md，可作为改造基础。）
2. **GL 资源生命周期迁移**：`Ship.init/cleanup` 的 VBO/FBO 创建删除排队到渲染线程
   （前置条件，任何 advance 离线程方案都依赖此项）。
3. **容器快照视图**：`ObjectRepository`/`LayeredRenderer` 提供渲染用不可变快照，
   消除 CME 类冲突。
4. **引擎级 advance/render 拆分**：以上全部完成后才具备条件。

## 远期候选：native 共享内存消费队列（已记录，暂缓执行）

思路（2026-08 提出，待 3→1→2 录制优化收尾并重新 profile 后再评估）：

- **机制**：命令不再构造成 Java 对象，而是以二进制流写入堆外固定环形队列
  （`Unsafe`/direct ByteBuffer，SPSC/MPSC、游标缓存行对齐防 false sharing）；
  启动时一次 JNI 把内存地址交给 native，native 起常驻消费线程自旋+退避轮询游标，
  用已 vendor 的 glad 直接调 GL——逐帧零 JNI，fence 回收也只是 Java 侧读消费游标。
- **基础设施已具备**：`native/` 模块已有 glad 与构建链（含 Windows 交叉编译），
  且已有 SpriteBatch/EngineBatch/Particle 等 JNI 批量渲染器可作先导改造对象。
- **不打当前瓶颈**：v29 profile 显示渲染线程几乎不钳制，瓶颈在主线程录制侧 ~29%；
  本方案省的是回放侧 JNI（每调用 1~3ns），短期收益有限，须以新 profile 数据为准。
- **真正价值在终态**：渲染循环（含 swapBuffers）整体下放 native 线程后，Java 主线程
  彻底不持 GL 上下文，命令流即内存格式，是录制开销的终极解法。
- **最大风险是兼容性**：GL 上下文归 native 线程后，BoxUtil/GraphicLib 等在 renderHook
  内直接调 LWJGL 的 mod 断路——要么同样录制，要么 `Display.makeCurrent` 来回切换
  （参考项目 starsector-render 正是在多线程 GL 适配翻车）。初版必须保留 mod 渲染的
  Java 侧通路。
- **建议路径**：先导验证（现有 native batch 渲染器改走共享 ring，独立可测、风险最小）
  → 有效后再升级为完整渲染线程后端（`Renderer` 接口新实现），mod renderHook 通路不变。

## 已封存路线：回放侧 immediate 顶点改 draw 数组（2026-08，尝试后放弃）

v36 profile 显示渲染线程回放末帧 `GL11.nglVertex2f/glVertex2f` 11,210 样本 +
libgallium 6,008，据此尝试将 `VertexStream.replay` 从逐顶点回调改为
「缓冲上传 + glVertexPointer 系 + glDrawArrays」。**经 v37~v43 七轮基准后止损回滚**，
未提交任何改动，HEAD 保持 8995666。失败教训：

- **黑屏根因链**：数组化后段内 `glColor/glTexCoord/glNormal` 只烘焙进顶点数组，真实 GL
  的 current 值不再更新；而批次间交错的 `glCallList`（把 begin..end 切成跨批的元凶）
  其显示列表消费**调用时刻**的 current color 做纹理调制，读到滞留值后场景精灵整体变黑。
  v40 修 flip 污染（mixed 批次开放段数据被覆盖，真 bug）、v41 做属性 current 值回同步，
  均未根治；v42 A/B 实锤强制逐顶点 sink 路径画面恢复（49.03 FPS，无收益）；
  v43 整段 sink + 预扫描重构仍黑屏（基准 FAILED）。
- **结论**：immediate 模式的隐性状态依赖（current 值的调用时刻语义、glCallList 交错）
  比预期深，完整建模的状态机成本高于收益。且回放侧从来不是帧率约束（渲染线程仅
  37% 忙），该路线预期收益本就只是抬高上限。
- **留存的正确认知**：VertexStream 字节流录制格式本身没问题；若未来重启此方向，
  前提是把 `glCallList` 也纳入命令建模（显示列表内容离线数组化），否则跨批切割
  问题会反复出现。

## 与本阶段已交付成果的关系

- **多线程舰船 AI**（已实装，commit 8cbfb22）：AI 计算并行化不涉及 GL 与实体容器
  结构修改（屏障后才由主线程消费命令），是本阶段「并行化收益」的主要落点；
  基准 gl_benchmark 90s：38.67 → 44.94 FPS（+16.2%），主线程 AI 采样 -81%。
- 渲染/逻辑分离的上述硬阻塞同样约束「渲染指令队列化」的最小版本——渲染代码几乎
  处处先写状态再调 GL，命令捕获必须在写之后同线程进行，等于 render 整体迁移，
  因此初版不做任何形式的部分队列化实装（避免半成品）。
