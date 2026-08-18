package github.kasuminova.ssoptimizer.bridge.opengl;

/**
 * 录制侧单个生产者线程的帧录制上下文：收敛主线程 GL 录制热路径上分散在
 * 多个 ThreadLocal 的逐调用查找（v36 profile：{@code ThreadLocal.getEntry/
 * getEntryAfterMiss} 合计约 2,067 样本），一次获取后帧内直传本对象引用。
 * <p>
 * 收敛内容：client pointer 快照状态（原 {@code POINTER_STATES}）、FRAMEBUFFER
 * 绑定跟踪（原 {@code FRAMEBUFFER_BINDING}）、immediate 顶点流（原
 * {@code VERTEX_STREAMS}）。
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
}
