# Native 渲染命令化与录制链路降载设计

## 背景与动机

v52 实机 profile（渲染线程分离模式，战斗场景）暴露三组相关联的热点：

1. **渲染线程 LWJGL 能力查找**：`RealSyncOpsImpl.memoryBarrier` →
   `GL42.glMemoryBarrier` → `GLContext.getCapabilities` → `ThreadLocal.get`，
   自身时间 60.0s（12.1%）。LWJGL 的每次静态 GL 调用内部都要经
   `GLContext.getCapabilities()` 做 ThreadLocal 查找取函数指针，有序写入
   （`MappedWriteBridgeImpl.executeOrderedWrite`）等高频路径被放大。
2. **引擎渲染 Java 回退**：`EngineRenderHelper.renderEngineStripPassFallback`
   自身 9.6s（3.8%）。分离模式下 `NativeRuntime` 主动置 `glReady=false`
   （主线程无 GL context，glad 直调必崩），全部 native GL 加速路径禁用；
   `EngineBatchImpl` 合批链路（收集→扁平化→nativeFlushBatch 环形 VBO）
   也因此整体关闭（其 Java 回退 flush 每帧两次 binding 回读 = 全管线 drain），
   引擎渲染退回「每 pass 一次 glBegin..glEnd（6 顶点）+ 穿插状态命令」的
   录制路径。
3. **顶点流缓冲借还**：`VertexStreamBufferPool.acquire` 自身 13.5s（5.3%）。
   单次成本已压至低位，但引擎 Java 回退是最大小批次生产者，flush 频率
   把「MPMC 全局池轮询 + 跨核原子计数」放大到可见量级。

三者的共同根因：**native 渲染能力被隔离在渲染队列体系之外**。glad 函数指针
不需要 context 即可加载（`glXGetProcAddressARB`/`wglGetProcAddress`），
真正受线程约束的只是「GL 调用必须在持有 context 的线程执行」——而渲染线程
恰恰持有真实 context。本设计把 native 执行并入渲染队列：录制侧只做纯 CPU
编码，native 渲染作为命令在渲染线程执行。

## 设计一：渲染线程 native 执行基座

### glad 渲染线程初始化

- `NativeRuntime` 新增 `ensureQueueGlReady()`：`loadModule(render)` 成功后
  调用既有 `nativeInitGl()`（C++ 侧 `static int loadResult` 幂等），
  结果缓存。**不改写 `glReady` 标志**——`isGlReady()` 的语义保持
  「主线程可直接 glad 调用」，分离模式下恒 false，主线程 helper 继续走
  录制回退，行为不变。
- 渲染线程启动（`RenderQueueImpl.renderLoop` 入口）调用一次并记日志；
  失败时 native 命令通道整体不可用（各命令走 Java 回退，语义正确）。

### native sync ops（消除热点 1）

- native-render 新增 `ssoptimizer_sync_ops.cpp`：`fenceSync/waitSync/
  clientWaitSync/deleteSync/memoryBarrier` 五个 JNI 入口，glad 直调，
  sync 句柄以 `jlong` 传递（`RealSyncOps` 句柄本就是不透明 Object，
  `Long` 装箱即可）。
- Java 侧 `bridge/opengl/NativeSyncOpsImpl implements RealSyncOps`。
  `RenderQueueImpl` 在渲染线程 glad 就绪后
  `BridgeSupport.syncOpsForTesting` 同款装配点替换默认实现（新增正式
  装配方法 `installSyncOps`，测试注入通道保留）。
- `RealSyncOps` javadoc 已约束「只在持有 GL context 的线程调用」
  （渲染线程 / aux 原生线程），glad 对两者均合法；调用契约不变。
- 收益：sync ops 从「LWJGL 静态调用 + ThreadLocal 能力查找」变为单次
  JNI + glad 函数指针直跳，消除 12.1% 的 `ThreadLocal.get` 热点。

## 设计二：NativeRenderCommand 接口（消除热点 2）

### 核心接口

```java
// sso-render: common/render/ncommand 包
/**
 * 录制侧编码、渲染线程 native 执行的批次渲染命令。
 * 实现类负责：录制侧把渲染参数编码进池化缓冲；执行侧经
 * NativeRenderExecutor 单次 JNI 完成绘制。
 */
public interface NativeRenderCommand extends GlCommand {
    /** 渲染线程执行入口：display list 编译窗口内必须走 Java 回退。 */
    @Override
    void execute();
}
```

配套 `NativeRenderExecutor`（渲染线程 confined，每渲染类型一个单例）：

```java
public interface NativeRenderExecutor {
    /** 渲染线程惰性初始化（VBO 等 GL 资源），返回是否可用。 */
    boolean ensureInitialized();
    /** 单次 JNI 批次绘制；glad 直调，恢复自身触碰的全部 GL 状态。 */
    void executeNative(ByteBuffer encoded);
    /** display list 编译窗口的 Java 回退（immediate 等价路径）。 */
    void executeFallback(ByteBuffer encoded);
}
```

### 与渲染队列体系的并入点

- **顺序性**：命令经 `BridgeSupport.enqueue` 落帧，天然保持与顶点流
  flush、状态命令的相对顺序（引擎渲染在舰船 push/pop 矩阵栈内执行，
  矩阵命令先录制先执行，无需额外处理）。
- **串合并边界**：`NativeRenderCommand` 不实现 `MergedBatchCommand`，
  在帧命令列表中天然切开顶点批次串——其前后串以 head/tail 协议正常
  收口，无需特判。
- **合并器状态失效**：native 执行经 glad 改动真实 GL 状态，绕过
  `VertexArrayBatch` 的 current 值/去重缓存。命令执行完毕必须调用
  合并器的外部状态失效钩子（`onExternalStateChange()` 同款）。
- **display list 编译窗口**：执行侧检测
  `BridgeSupport.isCompilingDisplayList()`——窗口内驱动按指针捕获客户端
  数组、且 `glBufferSubData` 等不可编译，必须走 `executeFallback`
  （immediate 逐指令，编译期按数据捕获，语义与现状一致）。
- **缓冲来源**：编码缓冲复用既有 `BufferSnapshotPoolImpl`（direct
  ByteBuffer 池，快照命令同款生命周期：录制侧借出编码 → 命令执行完
  finally 归还），稳态零分配。
- **失败降级**：glad 未就绪 / native 库缺失时，录制侧在 enqueue 前
  直接走既有 Java 回退路径（`EngineRenderHelper`），命令不产生——
  与现状行为完全一致，无半初始化状态。

### 首类接入：引擎渲染

复用既有资产，不重写 C++：

- **录制侧**（主线程，`EngineBatchImpl.render` 新路径）：
  `gather()`（既有，纯 CPU accessor 读取）→
  `EngineInstanceCollector.flatten()`（既有）写入池化 direct 缓冲 →
  enqueue `EngineNativeRenderCommand`。主线程零 GL 调用，
  分离模式的「binding 回读 drain」问题不复存在。
- **执行侧**（渲染线程，`EngineNativeExecutor`）：
  - `ensureInitialized`：渲染线程惰性创建环形 VBO 对（既有
    `DynamicVbo`，glGenBuffers 等在渲染线程执行，天然合法）；
  - `executeNative`：既有 `EngineBatchNative.nativeFlushBatch`
    （环形写入偏移随命令携带/回传，executor 持有偏移状态）；
  - `executeFallback`：display list 窗口内按扁平化缓冲逐实例展开为
    immediate 调用（`EngineInstanceCollector.expandXxxVertices` 既有）。
- 开关：`-Dssoptimizer.render.shipengine.queue=true`（默认 true），
  false 回退现状（分离模式禁用合批、走 `EngineRenderHelper` 录制路径）。
- 原「分离模式构造期禁用合批」逻辑随本路径上线移除——禁用理由
  （Java 回退 flush 的回读 drain）在命令化后已不存在。

### 后续接入候选（同接口，不特判）

`SpriteBatchNative.nativeSubmit/nativeFlush`、`ParticleBatchHelper`、
`TexturedStripRenderHelper` 均为同一形态（Java 编码 → 单次 JNI 绘制），
后续轮次按本接口逐个接入即可，每个只需提供一个 `NativeRenderExecutor`
实现。

## 设计三：顶点流缓冲 SPSC 返还通道（削减热点 3 的乘数）

现状：归还（渲染线程）与借出（主线程/aux 生产者）全部经过全局
`MpmcUnboundedXaddArrayQueue`——poll 侧多生产者 xadd 竞争 + 跨核
cache-line 竞争；`trackBorrow` 在补货循环内 per-buffer 做原子计数
（补 32 个 = 64+ 次原子操作）；`release` 每次都读 `queue.size()`。

改进（语义与保留策略不变）：

1. **SPSC 返还 inbox**：每个 `RecordingContext` 持有有界
   `SpscArrayQueue<byte[]>`（容量 32）。`VertexBatchCommand.setData`
   记录来源 inbox；渲染线程执行完把缓冲 offer 回**来源线程的 inbox**
   （渲染线程是每个 inbox 的唯一生产者、来源线程是唯一消费者，
   无 CAS 只有有序写）。inbox 满则落全局池既有 release 路径。
   aux 线程的 immediate 直执路径归还到自身 inbox（同线程 offer/poll，
   SPSC 语义成立）。
2. **借出顺序**：`acquire` 先 drain 本线程 inbox 进本地预借栈
   （零竞争），再走既有本地栈 → 全局补货 → 新建路径。
3. **计数一致性**：inbox 中的缓冲视同「在途借出」——离开全局池时已
   计入 `inFlight`，进 inbox 不做账；仅当 inbox 溢出落全局 release
   时才走既有 `inFlight` 递减。保留上限启发式不受影响。
4. **原子计数批量化**：全局补货按桶聚合成每补货一次
   `addAndGet(n)` + 峰值检查一次（原 per-buffer 32×2 次原子 → 1~2 次）。
5. ~~**`size()` 采样化**~~（实施时回退）：评估后发现
   `MpmcUnboundedXaddArrayQueue.size()` 为 O(1) 两次 volatile 读，
   采样化会打破「超限归还即丢」的精确语义（既有测试基线），
   收益不抵语义损失，保留逐次检查。

## 测试策略

- native sync ops：`NativeSyncOpsImpl` 的句柄传递/装配逻辑单测
  （不触碰真实 GL；真实 glad 路径走冒烟验证）；既有 `RealSyncOps`
  假实现注入的测试不受影响。
- NativeRenderCommand：编码内容单测（flatten 输出与既有
  `EngineRenderHelperTest` 校准基线一致）；命令执行分流
  （display list 窗口 → fallback、glad 未就绪 → 录制侧不产生命令）
  单测；执行侧真实 GL 走冒烟。
- SPSC 通道：沿用 `VertexStreamBufferPool` 既有测试基线 + 新增
  inbox 借还/溢出落池/计数平衡（`inFlight` 在 inbox 滞留期间不泄漏）
  单测。
- 全量：`./gradlew build deployMod` + `tools/smoke_test_game_launch.sh`
  冒烟；战斗场景实机验证由人工进行。

## 实施顺序与提交划分

1. native sync ops + 渲染线程 glad 初始化（独立提交）
2. SPSC 返还通道 + 计数批量化（独立提交）
3. NativeRenderCommand 框架 + 引擎渲染接入（独立提交）

每步独立可回退（开关或装配点回滚），互无编译期依赖。
