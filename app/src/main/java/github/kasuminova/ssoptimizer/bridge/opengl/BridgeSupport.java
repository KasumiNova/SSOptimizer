package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import org.lwjgl.LWJGLException;

import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

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
    /**
     * 状态命令去重开关：{@code -Dssoptimizer.render.statededup=false} 关闭，
     * 默认开启（连续相同的高频状态命令只入队一次，见 {@link StateDedup}）。
     * 静态可变字段供测试切换；uninstall 不重置（系统属性进程级语义）。
     */
    static volatile boolean stateDedupEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.statededup", "true"));

    private static volatile BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
    /** 池化命令对象（录制线程借出、渲染线程归还，见 {@link CommandPool} 的生命周期约束）。 */
    private static volatile CommandPool<DrawCommand> drawCommands = new CommandPool<>(DrawCommand::new);
    private static volatile CommandPool<VertexBatchCommand> vertexBatches = new CommandPool<>(VertexBatchCommand::new);
    private static volatile CommandPool<PointerSnapshotGroup> snapshotGroups = new CommandPool<>(PointerSnapshotGroup::new);
    /** 顶点流编码缓冲池（录制线程借出、渲染线程归还，见 {@link VertexStreamBufferPool}）。 */
    private static volatile VertexStreamBufferPool vertexStreamBuffers = new VertexStreamBufferPool();
    /**
     * 录制侧逐线程的帧录制上下文（见 {@link RecordingContext}）：统一持有
     * client pointer 状态、FRAMEBUFFER 绑定跟踪与 immediate 顶点流。
     */
    private static final ThreadLocal<RecordingContext> RECORDING_CONTEXT = ThreadLocal.withInitial(RecordingContext::new);
    /**
     * 主录制线程（游戏主线程）的帧录制上下文缓存：每帧边界（swap）获取一次
     * 后帧内直读，录制热路径不再逐调用走 ThreadLocal 查找（v36 profile 热点）。
     * 只由主线程写入；读者校验 owner 后再用，aux 生产者线程仍走各自实例。
     */
    private static volatile RecordingContext mainRecordingContext;
    /**
     * VBO id stash 单次批量预生成的个数。相对旧值 64 加大：v45c/v47 profile
     * 显示 LazyLib LazyFont 每帧 createText 新建 SpriteBatch（glGenBuffers），
     * stash 耗尽时主线程阻塞批量补货（getInternal park 1,065/535 样本的大头）；
     * 512 覆盖多帧消耗，配合低水位异步补货（{@link #refillBufferIdStashIfLow()}）
     * 使主线程 stash 恒有货。
     */
    static final int BUFFER_ID_STASH_BATCH = 512;
    /** stash 低水位阈值：渲染线程帧尾补货触发线（低于此值补一批 BATCH）。 */
    static final int BUFFER_ID_STASH_LOW_WATER = 256;
    /**
     * VBO id 预生成 stash：{@link GL15#glGenBuffers()}/{@link ARBVertexBufferObject#glGenBuffersARB()}
     * 单值形式的录制侧 id 池。游戏战斗期逐帧创建 VBO（SpriteBatch/粒子等），
     * 逐个走阻塞通道取 id 会把主线程耗在等渲染线程上（v35 profile：阻塞等待
     * 占主线程约 25%，其中 glGenBuffers 占绝对大头）；stash 命中时零阻塞，
     * 空时一次阻塞批量预生成 {@value #BUFFER_ID_STASH_BATCH} 个摊销往返。
     * id 的唯一性由渲染线程的真实 glGenBuffers 保证，返回顺序无语义。
     */
    private static volatile ConcurrentLinkedQueue<Integer> bufferIdStash = new ConcurrentLinkedQueue<>();
    /** stash 当前元素计数（低水位补货判断用；随 offer/poll 增减，与 stash 内容一致）。 */
    private static volatile AtomicInteger bufferIdStashCount = new AtomicInteger();
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
        RECORDING_CONTEXT.remove();
        mainRecordingContext = null;
        executedArrayBufferBinding = 0;
        drawCommands = new CommandPool<>(DrawCommand::new);
        vertexBatches = new CommandPool<>(VertexBatchCommand::new);
        snapshotGroups = new CommandPool<>(PointerSnapshotGroup::new);
        vertexStreamBuffers = new VertexStreamBufferPool();
        bufferIdStash = new ConcurrentLinkedQueue<>();
        bufferIdStashCount = new AtomicInteger();
    }

    static RenderQueue queue() {
        RenderQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("[SSOptimizer] bridge 的 RenderQueue 未安装（install 未被调用）");
        }
        return q;
    }

    /**
     * 帧提交收口（不等待）：swap 后刷新主录制线程的帧上下文缓存——
     * 主线程每帧此处获取一次 {@link RecordingContext}，帧内 GL 调用直读
     * 缓存，不再逐调用走 ThreadLocal（v36 profile 热点）。
     */
    static void swapFrames() {
        queue().swapFrames();
        refreshMainRecordingContext();
    }

    /** 帧提交收口（等待上一帧完成，Display.update 的帧尾调用形态）。 */
    static void swapFramesAndSync() {
        queue().swapFramesAndSync();
        refreshMainRecordingContext();
    }

    /**
     * 主录制线程（构造 RenderQueueImpl 的线程，即游戏主线程）在帧边界缓存
     * 上下文与当前录制帧（状态命令去重的相邻性判据），并重置去重缓存——
     * 跨帧不延续去重（GL 状态虽跨帧保持，保守起见帧间状态命令照常入队）。
     */
    private static void refreshMainRecordingContext() {
        if (RenderQueueImpl.isMainThread()) {
            RecordingContext ctx = RECORDING_CONTEXT.get();
            mainRecordingContext = ctx;
            ctx.dedupFrame = queue().currentFrame();
            ctx.stateDedup.invalidate();
        }
    }

    /**
     * 录制一条命令到当前帧。若当前线程的顶点流有未落帧的 immediate 操作，
     * 先把它打包落帧——流段命令与本命令在帧列表中的顺序即录制顺序。
     */
    static void enqueue(GlCommand command) {
        flushVertexStream();
        queue().submit(command);
    }

    /**
     * 录制一条可去重的状态命令（glBindTexture/glEnable/glDisable/glBlendFunc
     * 等，见 {@link StateDedup} 的类型常量）：与上一条已入队的状态命令
     * 类型参数完全相同、且期间帧命令列表无任何插入时跳过（不产生命令）；
     * 否则按 {@link #enqueue(GlCommand)} 落帧并记录指纹。任何插入——含
     * glCallList（显示列表执行绕过录制侧）、aux 生产者线程并发提交、顶点流
     * 落帧、其他命令——都会经帧提交序号（{@code RenderFrame.commitSeq}）打断
     * 相邻性，保证去重永不跨越「状态可能已被改变」的边界（旁路审计见
     * docs/design/render-state-dedup.md）。
     *
     * @param type    状态命令类型（{@link StateDedup} 常量）
     * @param a,b,c,d 参数槽（最多 4 个 int；float 由调用点转位模式）
     * @param command 真实 GL 调用命令体
     */
    static void enqueueState(int type, int a, int b, int c, int d, GlCommand command) {
        RecordingContext ctx = recordingContext();
        if (!stateDedupEnabled) {
            enqueue(command);
            return;
        }
        RenderFrame frame = ctx.dedupFrame;
        if (frame == null) {
            frame = queue().currentFrame();
        }
        StateDedup dedup = ctx.stateDedup;
        if (dedup.shouldSkip(frame, type, a, b, c, d)) {
            return;
        }
        enqueue(command);
        dedup.record(frame, type, a, b, c, d);
    }

    /**
     * 当前线程的帧录制上下文：主录制线程命中帧边界缓存（每帧一次
     * ThreadLocal 获取，帧内直读），其他线程每次经 ThreadLocal 获取。
     */
    static RecordingContext recordingContext() {
        RecordingContext cached = mainRecordingContext;
        if (cached != null && cached.owner == Thread.currentThread()) {
            return cached;
        }
        return RECORDING_CONTEXT.get();
    }

    /** 当前线程的 immediate 顶点流（bridge 的 glBegin/glVertex* 族录制入口）。 */
    static VertexStream vertexStream() {
        return recordingContext().vertexStream;
    }

    /**
     * 顶点流落帧：当前线程已累计的 immediate 操作移交给池化的
     * {@link VertexBatchCommand} 追加进当前帧（空流不产生任何命令）。触发点：
     * glEnd、任一非流式命令（见 {@link #enqueue(GlCommand)}）、阻塞通道
     * drain-first 之前——保证流段与其他命令/回读的相对顺序与原调用序列
     * 逐指令等价。缓冲所有权移交（{@link VertexStream#transferBuffer()}），
     * 渲染线程执行完经 {@link VertexStreamBufferPool} 归还，稳态零拷贝零分配。
     */
    static void flushVertexStream() {
        VertexStream stream = recordingContext().vertexStream;
        if (stream.isEmpty()) {
            return;
        }
        int length = stream.length();
        byte[] data = stream.transferBuffer();
        VertexBatchCommand batch = vertexBatches.acquire();
        batch.setData(data, length);
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
            swapFrames();
        }
        return q.get(getter);
    }

    /** {@link #blockingGet(Callable)} 的无返回值形式。 */
    static void blockingWait(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            flushVertexStream();
            swapFrames();
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
            swapFrames();
        }
        return q.getUncounted(getter);
    }

    /** {@link #blockingGetResource(Callable)} 的无返回值形式。 */
    static void blockingWaitResource(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            flushVertexStream();
            swapFrames();
        }
        q.waitUncounted(task);
    }

    /**
     * 取一个 VBO id（{@link GL15#glGenBuffers()}/{@link ARBVertexBufferObject#glGenBuffersARB()}
     * 单值形式的入口）：stash 非空零阻塞出队；空则走一次资源申请阻塞通道，
     * 由渲染线程批量预生成 {@value #BUFFER_ID_STASH_BATCH} 个 id——返回首个，
     * 其余入池。并发录制线程同时发现池空时会各自补货一批，id 唯一性由
     * 真实 glGenBuffers 保证，多发一批无害。稳态下阻塞兜底几乎不触发：
     * 渲染线程每帧帧尾经 {@link #refillBufferIdStashIfLow()} 低水位补货。
     */
    static int acquireBufferId() {
        Integer stashed = bufferIdStash.poll();
        if (stashed != null) {
            bufferIdStashCount.decrementAndGet();
            return stashed;
        }
        int[] generated = blockingGetResource(() -> {
            IntBuffer ids = ByteBuffer.allocateDirect(BUFFER_ID_STASH_BATCH * 4).asIntBuffer();
            org.lwjgl.opengl.GL15.glGenBuffers(ids);
            int[] batch = new int[BUFFER_ID_STASH_BATCH];
            ids.get(batch);
            return batch;
        });
        for (int i = 1; i < generated.length; i++) {
            bufferIdStash.add(generated[i]);
            bufferIdStashCount.incrementAndGet();
        }
        return generated[0];
    }

    /**
     * stash 低水位补货（渲染线程调用，Display.update 命令体前置）：计数低于
     * {@value #BUFFER_ID_STASH_LOW_WATER} 时直接在渲染线程真实 glGenBuffers
     * 一批 {@value #BUFFER_ID_STASH_BATCH} 个入 stash——补货不经过主线程阻塞
     * 通道，帧尾执行完后下一帧录制开始时 stash 已就位，主线程
     * {@link #acquireBufferId()} 恒零阻塞（v45c/v47 profile：LazyFont 每帧
     * createText 新建 SpriteBatch 的 glGenBuffers 高频路径）。
     */
    static void refillBufferIdStashIfLow() {
        if (bufferIdStashCount.get() >= BUFFER_ID_STASH_LOW_WATER) {
            return;
        }
        int[] generated = new int[BUFFER_ID_STASH_BATCH];
        IntBuffer ids = ByteBuffer.allocateDirect(BUFFER_ID_STASH_BATCH * 4).asIntBuffer();
        org.lwjgl.opengl.GL15.glGenBuffers(ids);
        ids.get(generated);
        for (int id : generated) {
            bufferIdStash.add(id);
            bufferIdStashCount.incrementAndGet();
        }
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
        return recordingContext().pointerState;
    }

    /** 录制侧（当前线程）跟踪的 FRAMEBUFFER 绑定。 */
    static int framebufferBinding() {
        return recordingContext().framebufferBinding[0];
    }

    /** 录制侧（当前线程）更新 FRAMEBUFFER 绑定跟踪（bridge 的 bindFramebuffer 调用点维护）。 */
    static void setFramebufferBinding(int framebuffer) {
        recordingContext().framebufferBinding[0] = framebuffer;
    }

    /** 录制侧（当前线程）的 GL 状态仿真（bridge 各 setter/getter 调用点维护/查询）。 */
    static SimulatedGlState simulatedState() {
        return recordingContext().simulatedState;
    }

    /**
     * GL 上下文重建后的聚合簿记复位（Display.create/setDisplayMode/setFullscreen 成功
     * 后由主线程调用）：录制侧状态仿真归零（{@link SimulatedGlState#onContextRecreated()}），
     * 并清空 VBO id stash——stash 内预生成的 id 全部属于已销毁的旧上下文，
     * 继续分发出去的都是死 id。清空后由渲染线程帧尾
     * {@link #refillBufferIdStashIfLow()} 在新上下文里重新补货。
     */
    static void onContextRecreated() {
        simulatedState().onContextRecreated();
        bufferIdStash = new ConcurrentLinkedQueue<>();
        bufferIdStashCount = new AtomicInteger();
    }

    /**
     * 当前线程是否主录制线程（游戏主线程）：getter 仿真短路仅对主线程开放——
     * aux 线程簿记不含主线程的状态流，一律回退阻塞通道保语义。
     */
    static boolean isMainRecordingThread() {
        return RenderQueueImpl.isMainThread();
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

    /** 顶点流换缓冲时从池借取（{@link VertexStream#transferBuffer()} 用）。 */
    static byte[] acquireVertexStreamBuffer(int minCapacity) {
        return vertexStreamBuffers.acquire(minCapacity);
    }

    /** 归还顶点流缓冲（仅 {@link VertexBatchCommand#execute()} 的 finally 调用）。 */
    static void releaseVertexStreamBuffer(byte[] data) {
        vertexStreamBuffers.release(data);
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
