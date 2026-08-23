package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;

/**
 * 录制侧单个生产者线程的帧录制上下文：收敛主线程 GL 录制热路径上分散在
 * 多个 ThreadLocal 的逐调用查找（v36 profile：{@code ThreadLocal.getEntry/
 * getEntryAfterMiss} 合计约 2,067 样本），一次获取后帧内直传本对象引用。
 * <p>
 * 收敛内容：client pointer 快照状态（原 {@code POINTER_STATES}）、FRAMEBUFFER
 * 绑定跟踪（原 {@code FRAMEBUFFER_BINDING}）、immediate 顶点流（原
 * {@code VERTEX_STREAMS}）、状态命令去重（{@link StateDedup}）。
 * <p>
 * 访问约定（{@link BridgeSupport#recordingContext()}）：主录制线程（游戏主线程）
 * 在每帧边界（swap）获取一次并缓存到静态引用，帧内全部 GL 调用直读缓存
 * （volatile 读 + owner 校验，无 ThreadLocal 查找）；非主线程每次经
 * ThreadLocal 获取各自实例。实例非线程安全，线程隔离由访问约定保证。
 */
final class RecordingContext {
    /** 本实例的所属线程：缓存直读前校验当前线程，避免 aux 线程误用主线程实例。 */
    final Thread owner = Thread.currentThread();
    /** client pointer 快照状态（draw 命令录制时刻整体捕获用）。 */
    final ClientPointerState pointerState = new ClientPointerState();
    /** 录制侧逐线程的 FRAMEBUFFER 绑定跟踪（供 bridge 的 getter 短路，免阻塞往返）。 */
    final int[] framebufferBinding = new int[1];
    /** 录制侧逐线程的 immediate 顶点流缓冲（glBegin/glVertex* 族，见 {@link VertexStream}）。 */
    final VertexStream vertexStream = new VertexStream();
    /** 状态命令去重（连续相同的高频状态命令只入队一次，见 {@link StateDedup}）。 */
    final StateDedup stateDedup = new StateDedup();
    /** 录制侧 GL 状态仿真（getter 回读短路，见 {@link SimulatedGlState}）。 */
    final SimulatedGlState simulatedState = new SimulatedGlState();
    /**
     * 主线程仿真 getter 的 aux 活动同步纪元：值为上次屏障再同步（或判定无 aux 活动）
     * 时的 {@code RenderQueueImpl.auxSubmissionEpoch()}。与当前纪元不等即 aux 生产者
     * 线程（BoxUtil 后台线程等）向命令流混入过簿记外的状态改动，主线程仿真簿记
     * 不再可信，需屏障再同步（见 {@code BridgeSupport.resyncSimulatedStateIfAuxDirty()}）。
     */
    long auxStateEpoch;
    /**
     * 最近一次 aux 再同步发生时的帧提交序号（{@code BridgeSupport} 的 swap 计数）：
     * 同一提交段内的后续仿真 getter 直接放行（每段至多一次屏障的滞后窗口）；
     * -1 表示从未再同步。
     */
    long auxResyncSeq = -1;
    /**
     * 当前录制帧（状态命令去重的相邻性判据来源）：主线程在每帧边界（swap）
     * 刷新；非主线程保持 null，由 {@link BridgeSupport#queue()} 现取当前帧。
     */
    RenderFrame dedupFrame;
    /**
     * 本线程顶点流的开放段延续标记：上一次落帧时流内 glBegin 段未收口
     * （{@link VertexStream#hasOpenSegment()}），下一批次以段开放状态开始——
     * 该批次必须走 immediate 兜底回放（见 {@link ImmediateVertexSink}）。
     */
    boolean vertexStreamStartsOpen;
}
