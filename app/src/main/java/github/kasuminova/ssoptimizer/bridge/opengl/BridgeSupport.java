package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.Callable;

/**
 * bridge 各入口类共享的静态支撑：队列句柄、录制/阻塞助手、缓冲快照池、
 * client pointer 快照状态。
 * <p>
 * 动机：bridge 的每个入口类（GL11/GL13/.../Display/GLContext）都需要同一套
 * 「提交命令 / 阻塞取回 / buffer 快照」原语，分散实现会产生十几份机械重复。
 * 收敛到本类后，入口方法体保持一行一命令的机械形态。
 * <p>
 * 快照池是进程级单例：直接缓冲池的价值在于跨帧复用，随用例新建会失去意义；
 * 池实现本身线程安全（录制在多生产者线程、归还在渲染线程）。
 */
final class BridgeSupport {
    private static volatile BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
    /** 池化命令对象（录制线程借出、渲染线程归还，见 {@link CommandPool} 的生命周期约束）。 */
    private static volatile CommandPool<DrawCommand> drawCommands = new CommandPool<>(DrawCommand::new);
    private static volatile CommandPool<VertexBatchCommand> vertexBatches = new CommandPool<>(VertexBatchCommand::new);
    private static volatile CommandPool<PointerSnapshotGroup> snapshotGroups = new CommandPool<>(PointerSnapshotGroup::new);
    private static final ThreadLocal<ClientPointerState> POINTER_STATES =
            ThreadLocal.withInitial(ClientPointerState::new);
    /** 录制侧逐线程的 FRAMEBUFFER 绑定跟踪（供 bridge 的 getter 短路，免阻塞往返）。 */
    private static final ThreadLocal<int[]> FRAMEBUFFER_BINDING = ThreadLocal.withInitial(() -> new int[1]);
    /** 录制侧逐线程的 immediate 顶点流缓冲（glBegin/glVertex* 族，见 {@link VertexStream}）。 */
    private static final ThreadLocal<VertexStream> VERTEX_STREAMS = ThreadLocal.withInitial(VertexStream::new);
    /**
     * 渲染线程侧簿记：命令流执行到当前位置的 GL_ARRAY_BUFFER 真实绑定。
     * 只由 bind 命令执行体与 pointer 重放（{@link PointerSnapshotGroup#apply()}）
     * 在渲染线程读写。
     */
    private static volatile int executedArrayBufferBinding;

    private static volatile RenderQueue queue;

    private BridgeSupport() {
    }

    /**
     * 安装命令消费者。各 bridge 入口类的 install 全部委托到此处，共享同一队列。
     *
     * @param renderQueue 渲染队列实例
     */
    static void install(RenderQueue renderQueue) {
        queue = renderQueue;
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        queue = null;
        POINTER_STATES.remove();
        FRAMEBUFFER_BINDING.remove();
        VERTEX_STREAMS.remove();
        executedArrayBufferBinding = 0;
        drawCommands = new CommandPool<>(DrawCommand::new);
        vertexBatches = new CommandPool<>(VertexBatchCommand::new);
        snapshotGroups = new CommandPool<>(PointerSnapshotGroup::new);
    }

    static RenderQueue queue() {
        RenderQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("[SSOptimizer] bridge 的 RenderQueue 未安装（install 未被调用）");
        }
        return q;
    }

    /**
     * 录制一条命令到当前帧。若当前线程的顶点流有未落帧的 immediate 操作，
     * 先把它打包落帧——流段命令与本命令在帧列表中的顺序即录制顺序。
     */
    static void enqueue(GlCommand command) {
        flushVertexStream();
        queue().submit(command);
    }

    /** 当前线程的 immediate 顶点流（bridge 的 glBegin/glVertex* 族录制入口）。 */
    static VertexStream vertexStream() {
        return VERTEX_STREAMS.get();
    }

    /**
     * 顶点流落帧：当前线程已累计的 immediate 操作拷贝进池化的
     * {@link VertexBatchCommand} 追加进当前帧（空流不产生任何命令）。触发点：
     * glEnd、任一非流式命令（见 {@link #enqueue(GlCommand)}）、阻塞通道
     * drain-first 之前——保证流段与其他命令/回读的相对顺序与原调用序列
     * 逐指令等价。
     */
    static void flushVertexStream() {
        VertexStream stream = VERTEX_STREAMS.get();
        if (stream.isEmpty()) {
            return;
        }
        VertexBatchCommand batch = vertexBatches.acquire();
        batch.fillFrom(stream);
        stream.reset();
        queue().submit(batch);
    }

    /**
     * 阻塞式取值（getter 回读通道）：先把当前录制帧提交进渲染线程
     * （{@link RenderQueue#swapFrames()}），再经阻塞通道执行 getter 并等待结果。
     * <p>
     * 先提交再取值是顺序正确性的关键：提交队列是 FIFO，帧任务排在同步任务之前执行，
     * getter 读到的一定是「此前全部已录制命令执行完」的 GL 状态——否则 getter 会越过
     * 尚未提交的录制命令读到滞后状态（如 glEnable 入队后立刻 glIsEnabled 返回旧值）。
     * 每次调用在资源加载期结束后计入
     * {@link github.kasuminova.ssoptimizer.common.render.queue.StallDetector}；
     * 加载期的成批一次性分配豁免（见 RenderQueueImpl 的熔断门控）。
     */
    static <T> T blockingGet(Callable<T> getter) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            // drain-first 必须包含未落帧的顶点流：getter 读到的是此前全部录制命令
            // 执行完的 GL 状态，顶点流是其中一部分
            flushVertexStream();
            q.swapFrames();
        }
        return q.get(getter);
    }

    /** {@link #blockingGet(Callable)} 的无返回值形式。 */
    static void blockingWait(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            flushVertexStream();
            q.swapFrames();
        }
        q.wait(task);
    }

    /**
     * 资源申请类阻塞取值（glGenBuffers/glCreateProgram/glGetUniformLocation 等），
     * 不计入 StallDetector；顺序语义同 {@link #blockingGet}（先提交当前帧再取值）。
     */
    static <T> T blockingGetResource(Callable<T> getter) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            flushVertexStream();
            q.swapFrames();
        }
        return q.getUncounted(getter);
    }

    /** {@link #blockingGetResource(Callable)} 的无返回值形式。 */
    static void blockingWaitResource(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            flushVertexStream();
            q.swapFrames();
        }
        q.waitUncounted(task);
    }

    /** 声明 {@link LWJGLException} 的阻塞任务。 */
    interface ThrowingTask {
        void run() throws LWJGLException;
    }

    /** 声明 {@link LWJGLException} 的阻塞取值。 */
    interface ThrowingGetter<T> {
        T get() throws LWJGLException;
    }

    /**
     * 阻塞通道执行并原样透传 {@link LWJGLException}（Display 创建/显示模式等
     * 调用方依赖受检异常做失败处理的场景）。
     */
    static void blockingWaitLwjgl(ThrowingTask task) throws LWJGLException {
        LWJGLException[] failure = new LWJGLException[1];
        blockingWait(() -> {
            try {
                task.run();
            } catch (LWJGLException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** {@link #blockingWaitLwjgl(ThrowingTask)} 的取值版本。 */
    static <T> T blockingGetLwjgl(ThrowingGetter<T> getter) throws LWJGLException {
        LWJGLException[] failure = new LWJGLException[1];
        T result = blockingGet(() -> {
            try {
                return getter.get();
            } catch (LWJGLException e) {
                failure[0] = e;
                return null;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return result;
    }

    /**
     * 录制侧的 client pointer 快照状态（按生产者线程隔离）。
     * 暴露给 bridge 包内与单测使用。
     */
    static ClientPointerState pointerState() {
        return POINTER_STATES.get();
    }

    /** 录制侧（当前线程）跟踪的 FRAMEBUFFER 绑定。 */
    static int framebufferBinding() {
        return FRAMEBUFFER_BINDING.get()[0];
    }

    /** 录制侧（当前线程）更新 FRAMEBUFFER 绑定跟踪（bridge 的 bindFramebuffer 调用点维护）。 */
    static void setFramebufferBinding(int framebuffer) {
        FRAMEBUFFER_BINDING.get()[0] = framebuffer;
    }

    /** 渲染线程簿记：命令流当前位置的 GL_ARRAY_BUFFER 绑定。 */
    static int executedArrayBufferBinding() {
        return executedArrayBufferBinding;
    }

    /** 渲染线程簿记更新（bind 命令执行体调用）。 */
    static void executedArrayBufferBinding(int buffer) {
        executedArrayBufferBinding = buffer;
    }

    /** 缓冲快照池（包内与单测可见）。 */
    static BufferSnapshotPool pool() {
        return pool;
    }

    /** 借出池化的 draw 命令（录制侧设置参数后落帧，渲染线程执行完归还）。 */
    static DrawCommand acquireDrawCommand() {
        return drawCommands.acquire();
    }

    /** 归还 draw 命令（仅 {@link DrawCommand#execute()} 的 finally 调用）。 */
    static void releaseDrawCommand(DrawCommand command) {
        drawCommands.release(command);
    }

    /** 借出池化的 pointer 快照组（{@link ClientPointerState#capture()} 用）。 */
    static PointerSnapshotGroup acquireSnapshotGroup() {
        return snapshotGroups.acquire();
    }

    /** 归还快照组（仅 {@link PointerSnapshotGroup#release()} 调用）。 */
    static void releaseSnapshotGroup(PointerSnapshotGroup group) {
        snapshotGroups.release(group);
    }

    /** 归还顶点流回放命令（仅 {@link VertexBatchCommand#execute()} 的 finally 调用）。 */
    static void releaseVertexBatch(VertexBatchCommand batch) {
        vertexBatches.release(batch);
    }

    /** 测试用：更换全新快照池，避免用例间经静态单例池串扰。 */
    static void resetPoolForTesting() {
        pool = new BufferSnapshotPoolImpl();
    }

    static void releaseSnapshot(ByteBuffer snapshot) {
        pool.release(snapshot);
    }

    /** 快照命令体：在渲染线程拿到池化快照执行，执行后归还。 */
    interface SnapshotCommand {
        void execute(ByteBuffer snapshot);
    }

    /**
     * 把 {@code src} 深拷贝入池后录制一条携带快照的命令；命令执行完（无论
     * 成败）归还快照。拷贝发生在录制时刻，调用方随后改写源 buffer 不影响命令。
     */
    static void enqueueSnapshot(ByteBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    /** {@link #enqueueSnapshot(ByteBuffer, SnapshotCommand)} 的 {@link DoubleBuffer} 版本。 */
    static void enqueueSnapshot(DoubleBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    /** {@link #enqueueSnapshot(ByteBuffer, SnapshotCommand)} 的 {@link FloatBuffer} 版本。 */
    static void enqueueSnapshot(FloatBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    /** {@link #enqueueSnapshot(ByteBuffer, SnapshotCommand)} 的 {@link IntBuffer} 版本。 */
    static void enqueueSnapshot(IntBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    /** {@link #enqueueSnapshot(ByteBuffer, SnapshotCommand)} 的 {@link ShortBuffer} 版本。 */
    static void enqueueSnapshot(ShortBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    private static void enqueueSnapshotCommand(ByteBuffer snapshot, SnapshotCommand command) {
        enqueue(() -> {
            try {
                command.execute(snapshot);
            } finally {
                pool.release(snapshot);
            }
        });
    }
}
