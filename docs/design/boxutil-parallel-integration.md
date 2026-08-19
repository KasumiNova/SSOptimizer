# BoxUtil 接入并行录制模式：技术方案与成本分析

状态：**分析完成（2026-08-19）**，供阶段 2 门禁决策参考。
材料：BoxUtil 完整反编译（`BoxUtilMod.jar` → `/tmp/boxutil-full-src/`，
`jars/backends/BoxUtilImpl.jar` → `/tmp/boxutil-impl-src/`，vineflower 1.12.0）；
我方代码以 feat/render-thread 分支当前 tip 为准。
本文引用的 BoxUtil file:line 均为上述两个反编译目录下的路径。

## 1. 现状协议全景

### 1.1 线程体系与 SharedDrawable 折叠

BoxUtil 启动 3 条常驻 aux 线程（`BUtil_BoxUtilBackgroundThread.java:19-23`，
3 固定线程池），各持一个 `new SharedDrawable(Display.getDrawable())`
（`:41/:56/:71`）：

| 线程 | 类 | 职责 |
|---|---|---|
| Rendering | `BUtil_RenderingThread` | 帧内三相位插件回调（BEGIN_RENDERING / BEGIN_ILLUMINATION / AFTER_RENDERING）+ 一份实体 submit 队列消费 |
| Logical | `BUtil_LogicalThread(isAux=false)` | 2D 实例数据：advance、submit、SSBO 压缩、compute 预计算 |
| Logical-Aux | `BUtil_LogicalThread(isAux=true)` | 3D 实例数据，同上镜像 |

线程体循环条件 = 主线程存活（`BUtil_BoxUtilBackgroundThread.java:164`）；
任何异常 → `BUtil_ThreadResource.pushThreadException` → 主线程下一帧
`checkShouldCloseGame()` 弹窗并 **`System.exit(1)`**
（`BUtil_ThreadResource.java:87-99`）。**结论：BoxUtil aux 线程上抛异常 = 杀游戏，
「拒绝/抛错」类处置不可用于 aux 线程。**

我方折叠：`SharedDrawable` 桥接为纯登记对象（bridge/opengl/SharedDrawable.java:26-70），
`makeCurrent` 不触碰 GL；aux 线程的全部 GL 调用已被 ASM 重定向为命令录制，
与主线程共享单渲染线程执行。

### 1.2 CPU 侧同步：Phaser 协议（`org/boxutil/config/BoxThreadSync.java:6-13`）

8 个 Phaser，host=游戏主线程。战斗侧时序（每帧）：

```
host advance (BUtil_CombatEFS.advance :57-74)
  └ arrive Logical.beginAdvance ──────────────────────────────┐
Logical ×2 (BUtil_LogicalThread.runBody :247-277)             │
  ├ await beginAdvance                                        │
  ├ tryGLSync(__SYNC_[AUX_]AFTER_RENDERING_HOST)   :249       │
  ├ runThreadPlugin(begin) / runEntityAdvance /               │
  │   runEntitySubmit / glMemoryBarrier(512)       :254-259   │
  ├ await beginPoolCompact → compactMemoryPool     :261-270   │
  ├ await beginInstanceCompute → preComputeInstance:271-272   │
  │   （rebindSSBO + glDispatchCompute + glMemoryBarrier(8192)│
  │    :219-233）                                             │
  ├ sendGLSync(__SYNC_[AUX_]FINISH_ADVANCE)        :273       │
  ├ arrive await Logical.finishAdvance             :274 ◄─────┤
  ├ runThreadPlugin(after advance)                 :275       │（与 host render 并发）
  └ sendGLSync(__SYNC_[AUX_]BEGIN_ADVANCE)         :276       │
                                                              │
host render 最低层 (BUtil_CombatEFS.Renderer.render :104-128) │
  ├ sendGLSync(__SYNC_FINISH_ADVANCE_HOST)         :116       │
  ├ arrive await Logical.finishAdvance             :117 ──────┘
  ├ tryGLSync(__SYNC_[AUX_]FINISH_ADVANCE)         :118-119
  ├ delayAdd / arrive await Rendering.beforeRendering / 
  │   host 侧 runEntitySubmit(false) / refreshCurrFrameState :122-125
  └ arrive await Rendering.beginRendering + tryGLSync(__SYNC_BEGIN_RENDERING) :126-127

Rendering 线程 (BUtil_RenderingThread.runBody :52-65)
  ├ await beforeRendering + tryGLSync(FINISH_ADVANCE_HOST) :53-54
  ├ runEntitySubmit(true)                                    :55
  ├ await beginRendering → runThreadPlugin(BEGIN_RENDERING)  :56-57
  ├ sendGLSync(__SYNC_BEGIN_ILLUMINATION)                    :58→:44
  ├ await beginIllumination → runThreadPlugin(BEGIN_ILLUMINATION) :59-60
  ├ sendGLSync(__SYNC_AFTER_RENDERING)                       :61→:48
  ├ await afterRendering → runThreadPlugin(AFTER_RENDERING)  :62-63
  └ sendGLSync(__SYNC_BEGIN_RENDERING)                       :64→:40

host render 最高层 (:131-159, 175-180, 202-207)
  ├ arrive await beginIllumination + tryGLSync(BEGIN_ILLUMINATION) :132-133
  ├ glBeginDraw / 光照 pass / mesh pass（纯命令）
  ├ sendGLSync(__SYNC_[AUX_]AFTER_RENDERING_HOST)  :176-177/203-204
  └ arrive await afterRendering + tryGLSync(AFTER_RENDERING) :178-179/205-206
```

### 1.3 GPU 侧同步：GL fence 协议（折叠后 = 队列内 Java 会合点）

10 个 fence 槽（`BUtil_ThreadResource.java:69-78`），产消均为
`glFenceSync`/`glWaitSync`（`:101-124`；`BoxConfigs.isCompatibleSync()` 默认
false（`BoxConfigs.java:38,306`），即 fence 路径生效）。
我方桥接（bridge/opengl/GL32.java:58-84）：`glFenceSync` → 录制
`SignalFenceCommand` 并立即返回句柄；`glWaitSync` → 录制 `WaitFenceCommand`，
执行时未 signal 则**帧悬挂续跑**（WaitFenceCommand.java:36-45 +
RenderQueueImpl.java:366-383），渲染线程不阻塞。

逐对核对（producer → consumer，帧关系）：

| fence | producer | consumer | 帧关系 |
|---|---|---|---|
| BEGIN_ADVANCE / AUX | Logical 线程 advance 尾 | host 下一帧 advance（:66-67） | 跨帧 |
| FINISH_ADVANCE / AUX | Logical 线程 advance 尾（:273） | host 最低层 render（:118-119） | 同帧 |
| FINISH_ADVANCE_HOST | host 最低层（:116） | Rendering 线程（:54） | 同帧 |
| BEGIN_RENDERING | Rendering 线程周期尾（:64） | host 最低层下一帧（:127） | 跨帧 |
| BEGIN_ILLUMINATION | Rendering 线程（:58） | host 最高层（:133） | 同帧 |
| AFTER_RENDERING | Rendering 线程（:61） | host 最高层（:179/206） | 同帧 |
| AFTER_RENDERING_HOST / AUX | host 最高层（:176-177/203-204） | Logical 线程下一帧 advance（:249） | 跨帧 |

**关键事实：所有同帧对都是「producer 的录制在真实时间上先于 consumer」。**
这是 §3 冲突点 #2 复核的基石。

### 1.4 aux 线程逐帧 GL 调用清单与阻塞通道触发点

| 调用 | 位置 | bridge 行为 |
|---|---|---|
| glFenceSync / glWaitSync / glDeleteSync | 三线程 fence 点 | 纯 Java 会合，**零阻塞** |
| glFlush（sendGLSync 内） | 同上 | 入队命令（GL11.java:478-480） |
| glBindBuffer + glMapBufferRange(WRITE, access=38/6) + glUnmapBuffer，或 glBufferSubData | submitInstance（BaseInstanceRenderData.java:210-274）、TrailEntity.submitNodes（TrailEntity.java:183-234） | 纯写映射走 BufferMapEmulator 仿真**零阻塞**；fallback 回退阻塞通道 |
| glGenBuffers + runtimeBufferIDCheck | SSBO init/realloc/compact（BUtil_InstanceDataMemoryPool.java:141-142/158-159/215-216/783-784） | stash 命中**零阻塞**（GL15.java:57-59）；stash 空回退阻塞资源通道 |
| glBufferStorage/glBufferData/glCopyBufferSubData/glInvalidateBufferData/glDeleteBuffers/glBindBufferBase | SSBO 池分配/压缩/换绑（同文件 :143-175, :217-228, :859-871） | 纯命令入队 |
| glUseProgram/glUniform/glDispatchCompute/glMemoryBarrier | preComputeInstance（BUtil_LogicalThread.java:217-233） | 纯命令入队 |
| glGetError | 仅线程启动健康检查（BUtil_BoxUtilBackgroundThread.java:133） | 阻塞通道，启动期一次性 |
| 第三方 BackgroundEveryFramePlugin 的 runBegin*/runAfter* | 三线程插件回调 | **内容不可枚举**（BoxUtil 本体不注册，由依赖模组经 CombatRenderingManager.java:53-67 注册） |

时序归属：Logical 线程的 submit/compute/compact 全部在 host advance 相位内
（finishAdvance phaser 之前）；**与 host render 窗口并发的 aux GL 是**：
Rendering 线程的 runEntitySubmit + 三相位插件回调，以及 Logical 线程的
runAfterAdvance 插件回调（:275）。

### 1.5 渲染产物的消费路径

aux 写入的 SSBO/TBO（实例矩阵、trail 节点）由 host 在最高层的
`ShaderCore.glBeginDraw` → `processIlluminationPass`/`processMeshCurrentLayout`
消费（BUtil_CombatEFS.java:135-151, 192）。跨「上下文」可见性在原版靠
fence 保证；折叠模型下单渲染线程顺序执行，可见性退化为**命令流顺序**，
由 §1.3 的 fence 序保证。

### 1.6 我方相关机制现状

- drain-first 阻塞通道：getter/资源申请先 `swapFrames()` 再取值
  （BridgeSupport.java:312-373）；swap 前 `flatten()` 封存全部段
  （RenderQueueImpl.java:269-280 → RenderFrame.java:174-194）；
  段封存后迟到写入 fail-fast（RenderSegment.java:53-60）。
- 段绑定：worker 经 `bindSegment` 直写段（BridgeSupport.java:179-195）；
  段内阻塞 fail-fast（`:331-337`）。aux 线程无绑定，`enqueue` 走
  `queue().submit()` → **帧的当前串行段**（`:202-211` →
  RenderQueueImpl.java:118-122 → RenderFrame.java:85-88，`serialSegment`
  为 volatile 最新串行段）。
- VBO id stash、BufferMapEmulator、getter 仿真均已存在；并行窗口/编排器
  概念尚未实现（阶段 2 未动工）。

## 2. 冲突点验证与修正（对照审计 §4 附注）

### 冲突 #1「aux drain 击穿分段不变量」——**确认成立，且触发面比审计估计更宽**

窗口内任一 aux 阻塞调用 → drain-first `swapFrames()` → `flatten()` 封存 →
worker 迟到写入 `IllegalStateException`（或编排器串行重跑降级，但帧已被
提前提交，内容拆散）。窗口内现实触发源：

1. BufferMapEmulator fallback：两条 Logical 线程可并发映射**同一 target
   37074**（2D/3D 池各持各的 `_GPU_LOCK`，互不互斥；
   BaseInstanceRenderData.java:226-241），仿真器 `PENDING` 以 target 为键
   （BufferMapEmulator.java:38,122），撞键即 fallback 回退阻塞通道；
2. bufferIdStash 耗尽时的 glGenBuffers 补货（BridgeSupport.java:383-405）；
3. 不可枚举的第三方 background 插件 getter。

### 冲突 #2「命令序语义偏移」——**降级：分析后不成立为语义错误，但需编排不变量守护**

窗口内 aux 提交落「当前串行段」（登记序先于并行段），flatten 后聚簇在
并行段之前。逐对核对 §1.3 全部 fence 对：**producer 的真实时间录制均先于
consumer，且 producer 所在段序 ≤ consumer 所在段序**——同帧对顺序在
flatten 后保持不变，跨帧对由帧序保证。`__SYNC_AFTER_RENDERING_HOST`
（host 最高层，窗口后串行段）→ Logical 线程下一帧消费，亦保持。
原版语义中 aux 与 host 命令本就跨上下文无序（仅 fence 定序），折叠模型下
聚簇不改变任何 fence 对序，**不产生「差一帧/闪烁」**。

残留两点真实风险（属新发现，非原 #2）：

- **#2a（潜伏死循环）**：若未来编排变更使某 fence 对的 signal 段序 >
  wait 段序，`WaitFenceCommand` 悬挂续跑会把 signal 命令圈进续跑子列表
  却永远执行不到（每次重跑都在 wait 处再抛 SuspendFrameException，
  RenderQueueImpl.java:372-383）——**永久自旋**，仅有一次 warn。
  当前协议下不可达，但无任何守卫。
- **#2b（卫生）**：aux 状态命令（glUseProgram/绑定族）聚簇进串行段会
  打断该段 StateDedup 相邻性（设计已保守失效，仅性能微损，无正确性问题）。

### 冲突 #3「审计材料不全」——**已解决**

完整 jar 反编译覆盖入口/桥接/线程/池/shader 全部类；§1 的协议表即为
全量验证结果。附两条对审计正文的修正：

- 审计 §4 称 BoxUtil「无游戏侧锚点」——**仅对 aux 线程成立**。战斗侧
  host hook 经 `addLayeredRenderingPlugin` 锚入层遍历
  （BUtil_ThreadResource.java:520 + settings.json:44 注册
  BUtil_CombatEFS），与 GraphicsLib/MagicLib 同机制，即并行编排中同样
  以 CustomCombatEntity 动态分流处理；campaign 侧为 CustomCampaignEntity
  （BUtil_CampaignRenderingPlugin），不在阶段 2 并行范围。
- fence 槽实为 10 个（审计只列了 3 个命名点），但语义已全量核对（§1.3）。

### 新发现冲突 #4：BufferMapEmulator 簿记非线程隔离（串行模式下已潜伏）

`BOUND/SIZES/PENDING` 为进程级 HashMap（BufferMapEmulator.java:31-39），
多录制线程并发 bind/map 同 target 时簿记互踩：轻则无意义 fallback 阻塞
（串行模式下 = stall），重则镜像张冠李戴。并行模式下 fallback 即冲突 #1
的引爆点。**此为方案 A 的必修项，且对串行模式也是修正。**

## 3. 方案 A（最小）：并行窗口内 aux 调用安全化 + 路由保持现状

设计判断：**路由不改**（aux 提交仍落当前串行段，§2 证明 fence 序保持），
只堵 drain 引爆点。四处改动：

1. **RenderQueueImpl：并行窗口状态 + 延迟同步通道**。
   - `openParallelWindow()/closeParallelWindow()`（编排器调用，开窗 =
     reserveSegments 之前，关窗 = join 屏障点）；
   - 窗口内非渲染线程的 `get/wait/getUncounted/waitUncounted` **不再
     drain-first**：任务挂入窗口延迟队列，调用线程 park 在任务 Future 上；
   - 关窗时 `flushDeferred()`：join 完成后先 `swapFrames()`（此时 flatten
     合法，worker 已全部 join）再把延迟任务 offer 进提交队列——getter 读到的
     仍是「此前全部命令执行完」的状态，drain-first 语义在窗口粒度上保持；
   - 窗口内主线程/其他路径的 `swapFrames()` 一律 fail-fast（把现在「静默
     拆帧 + 迟到写入崩」变成就地可诊断崩）。
   - 无死锁：窗口内主线程只等 worker join，不等任何 BoxUtil Phaser；
     BoxUtil 的 Phaser 会合点全部在窗口外的串行段（最低层/最高层 hook），
     被 park 的 aux 线程在关窗后放行，不阻碍主线程推进。
2. **BridgeSupport：阻塞四通道接窗口判定**（非主线程且窗口开启 → defer
   路径；主线程维持 fail-fast/仿真缺口语义）。约 30 行。
3. **BufferMapEmulator 线程隔离修正**（冲突 #4）：`BOUND/PENDING` 键改为
   按录制线程隔离（ThreadLocal 簿记或 (thread,target) 复合键），镜像按
   VBO 不变；消除跨线程撞键 fallback。
4. **编排器接入点**（阶段 2 本就新建）：开窗/关窗钩子 + 屏障序列固定为
   `join → flushDeferred → openNextSerialSegment`。
5. **BoxUtil 检测语义降级**：由「自动回退串行」改为「信息日志 + defer
   路径生效」；`-Dssoptimizer.render.parallel=false` 兜底不变，新增
   `-Dssoptimizer.render.parallel.boxutil=false` 单项回退。

成本：queue/bridge 层约 300-400 行含单测；不改编排器主体设计，不改 BoxUtil
一个字节。风险：defer 使窗口内 aux 阻塞调用的延迟从「立即 drain」变为
「等到屏障」（毫秒级，且 BoxUtil 自身协议上这些点不在关键路径）；
残余面 = 第三方 background 插件若有跨线程 CPU 自旋等待 GL 结果的协议
（不可枚举，见 §6 开放问题），症状为 aux 线程慢一拍而非崩溃。

收益：BoxUtil 从「并行模式不支持」名单移除；冲突 #4 连带修复串行模式
潜伏病；窗口守卫对一切未来模组/aux 生产者通用。

## 4. 方案 B（完整）：fence 协议接入段编排

在方案 A 全部内容之上追加：

1. **aux 共存段**：开窗时向帧登记一个「aux 段」（位置 = 窗口处），窗口内
   aux 提交路由到该段而非旧串行段——aux 命令在 flatten 序中的位置与
   真实时间一致，dedup 卫生问题解决。
   **代价**：aux 段是多写者（3 条 aux 线程），违反 RenderSegment 单写者
   契约——要么拆 3 个子段（按线程识别），要么给段加锁（性能倒退），
   要么新开一类 MultiWriterSegment。三者都是新的复杂度源头。
2. **fence 段序断言**：GL32 桥接在调试开关下为 Signal/Wait 命令附带段序
   校验（producer 段序 ≤ consumer 段序），覆盖冲突 #2a 的潜伏死循环。
   低成本，值得单独吸收。
3. **（B+，明确不推荐）** Mixin 注入 BUtil_RenderingThread/BUtil_LogicalThread
   的 runBody 相位点，编排器显式对齐 BoxUtil 相位与段边界。耦合 BoxUtil
   内部协议结构，跨版本脆弱，且 §2 已证明正确性不依赖它——过度设计。

成本：A 之上再加 300-500 行（多写者段 + 线程识别路由 + 断言设施），
外加与 BoxUtil 协议演化的长期耦合维护。收益：排序位置语义化 + 可诊断性，
**正确性增量为零**（A 已保证）。风险：多写者段引入新的并发面，恰在
最热路径上。

## 5. 推荐路线

1. **阶段 2 按原门禁计划上线**：BoxUtil 检测自动回退串行不变——并行主体
   不与 BoxUtil 兼容性耦合，风险最小。
2. **方案 A 作为紧随的独立增量立项**（不阻塞阶段 2 主体）：改动集中在
   queue/bridge 层，自带单测可独立验收；落地并实测稳定后，把 BoxUtil
   从回退名单移除（默认并行共存 + 单项开关兜底）。
3. **方案 B 不实施**；仅把「fence 段序断言」（B-2，几十行）并入方案 A
   的调试设施，覆盖 #2a。
4. 冲突 #4 的 BufferMapEmulator 修正可先行合入（与并行无关的独立修正）。

## 6. 验证计划

- **单测**（`:app:test`）：窗口内 defer（getter 不触发 swap、关窗 flush 后
  Future 完成且结果正确、flush 顺序 = 帧任务先于同步任务）；窗口内主线程
  drain fail-fast；BufferMapEmulator 双线程同 target 并发 map 不 fallback、
  镜像数据不串；fence 段序断言（构造倒挂场景验证断言触发）。
- **冒烟**：`tools/smoke_test_game_launch.sh` 带 BoxUtil（shader 开启）
  进战斗：0 错误 / 0 MixinApplyError / 无段封存 fail-fast /
  无 WaitFenceCommand 悬挂 warn（稳态零悬挂）。
- **基准**：`tools/benchmark_run.sh`，BoxUtil 在场下「方案 A 并行」vs
  「回退串行」avgFps 对比，验收沿用 ±3 噪声带且不低于基线 71.82。
- **帧抓取对比**：`-Dssoptimizer.debug.framecapture.dir/.frame` 连续抓帧，
  并行+BoxUtil 与串行+BoxUtil 逐帧比对（重点：trail/光照 pass/实例化实体）。
- **诊断计数**：defer 命中次数/滞留时长、emulator fallback 率
  （复用 `-Dssoptimizer.debug.buffermap`）、悬挂续跑次数——接日志，
  冒烟+基准双门禁归零（defer 允许非零，仅观测病理量级）。

## 7. 开放问题（未验证，需后续补材料）

1. **第三方 BoxUtil 依赖模组的 background 插件内容不可枚举**（本机未装
   此类模组；BoxUtil 本体不注册任何 background 插件）。defer 覆盖其阻塞
   调用，但若插件自建「CPU 自旋等 GL 结果」协议且等待点在窗口关键路径上，
   可能出现 aux 慢一拍/相位错位。需实机装典型依赖模组（如 shaderpack 类）
   回归。
2. **campaign 侧未逐行核对**（BUtil_CampaignEFS/BUtil_CampaignRenderingPlugin
   与战斗侧同构，fence/phaser 用法一致；阶段 2 不并行 campaign 渲染，
   窗口永不开启，影响为零）。campaign 并行化立项时需复核。
3. **`isCompatibleSync=true` 用户配置路径未实测**：fence 全消失仅剩
   Phaser，命令序无任何 GPU 级守护（串行模式下同样裸奔，属 BoxUtil 自有
   妥协）；方案 A 的正确性分析不依赖 fence 存在，但建议冒烟默认配置之外
   加一轮该开关验证。
4. **SSBO 池战斗中期 realloc/compact 的实测频率未知**（20s 节流 +
   增长触发，BUtil_InstanceDataMemoryPool.java:152）；其 glGenBuffers
   走 stash、copy/invalidate 走纯命令，理论上零阻塞，待基准期日志确认。
5. **BoxUtil 版本演化**：方案 A 只耦合「aux 线程在窗口内的行为」这一通用
   面，不耦合其内部协议；但 §1.3 的 fence 点对齐结论随 BoxUtil 大版本
   更新需重新核对。
