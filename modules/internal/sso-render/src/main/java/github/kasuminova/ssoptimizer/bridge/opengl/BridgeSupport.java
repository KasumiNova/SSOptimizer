package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;
import github.kasuminova.ssoptimizer.common.render.queue.ProbeSiteCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;
import org.lwjgl.LWJGLException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

/**
 * OpenGL 桥接入口共享的队列、录制上下文、阻塞通道与快照池。录制侧状态按生产者线程隔离，
 * 真实 GL 调用在渲染线程执行；aux 原生线程直接操作其共享上下文。
 */
final class BridgeSupport {
    private static final Logger LOGGER = Logger.getLogger(BridgeSupport.class);

    static volatile boolean stateDedupEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.statededup", "true"));

    static volatile boolean deferredGlEndEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.deferredGlEnd", "true"));

    static volatile boolean streamMatrixOpsEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.streamMatrixOps", "true"));

    static volatile int deferredGlEndThresholdBytes =
            Integer.getInteger("ssoptimizer.render.deferredGlEnd.thresholdBytes", 1 << 20);

    private static final boolean GL_ERROR_PROBE_COMMAND = "command".equals(
            System.getProperty(RenderQueueImpl.GL_ERROR_PROBE_PROPERTY, "off"));

    private static volatile BufferSnapshotPoolImpl pool = new BufferSnapshotPoolImpl();
    private static volatile CommandPool<DrawCommand> drawCommands = new CommandPool<>(DrawCommand::new);
    private static volatile CommandPool<VertexBatchCommand> vertexBatches = new CommandPool<>(VertexBatchCommand::new);
    private static volatile CommandPool<PointerSnapshotGroup> snapshotGroups = new CommandPool<>(PointerSnapshotGroup::new);
    private static volatile VertexStreamBufferPool vertexStreamBuffers = new VertexStreamBufferPool();
    private static final ThreadLocal<RecordingContext> RECORDING_CONTEXT = ThreadLocal.withInitial(RecordingContext::new);
    /** 仅主录制线程在帧边界写入，其他线程仅在 owner 匹配时读取。 */
    private static volatile RecordingContext mainRecordingContext;
    static final int BUFFER_ID_STASH_BATCH = 512;
    static final int BUFFER_ID_STASH_LOW_WATER = 256;
    private static volatile ConcurrentLinkedQueue<Integer> bufferIdStash = new ConcurrentLinkedQueue<>();
    private static volatile AtomicInteger bufferIdStashCount = new AtomicInteger();
    private static volatile int executedArrayBufferBinding;
    private static final int CLIENT_ATTRIB_STACK_MAX = 64;
    private static final int[] clientAttribMasks       = new int[CLIENT_ATTRIB_STACK_MAX];
    private static final int[] clientAttribArrayBuffer = new int[CLIENT_ATTRIB_STACK_MAX];
    private static int clientAttribDepth;

    /** 生产队列上只有渲染线程可写执行侧簿记，aux 原生线程的绑定属于各自共享上下文。 */
    private static boolean isBookkeepingThread() {
        final RenderQueue q = queue;
        return !(q instanceof RenderQueueImpl) || q.isRenderThread();
    }

    private static final int BIND_TRACE_CAPACITY = 64;
    private static final int BIND_TRACE_MASK     = BIND_TRACE_CAPACITY - 1;
    private static final BindTraceEvent[] BIND_TRACE = createBindTrace();
    private static final AtomicLong BIND_TRACE_SEQ = new AtomicLong();
    private static volatile java.util.function.IntSupplier arrayBufferBindingProbe =
            () -> org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);

    private static final class BindTraceEvent {
        long   seq;
        int    buffer;
        String origin;
        String recordThread;
    }

    private static BindTraceEvent[] createBindTrace() {
        final BindTraceEvent[] events = new BindTraceEvent[BIND_TRACE_CAPACITY];
        for (int i = 0; i < events.length; i++) {
            events[i] = new BindTraceEvent();
        }
        return events;
    }
    private static volatile int displayListCompileDepth;
    private static volatile Object mainGlContextToken = new Object();

    private static volatile RenderQueue queue;

    private static volatile long frameSubmitSeq;
    private static volatile java.util.function.Supplier<GlStateSnapshot> stateSnapshotSource =
            GlStateSnapshot::capture;
    private static volatile boolean auxResyncLogged;
    private static volatile RealSyncOps syncOps = RealSyncOpsImpl.INSTANCE;

    private static final java.util.List<Runnable> CONTEXT_RECREATED_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private BridgeSupport() {
    }

    static void install(RenderQueue renderQueue) {
        if (queue != renderQueue) {
            UniformLookup.clear();
        }
        queue = renderQueue;
        if (renderQueue instanceof RenderQueueImpl) {
            ((RenderQueueImpl) renderQueue).setFrameFailureHook(
                    BridgeSupport::reconcileArrayBufferBindingAfterFailure);
        } else if (renderQueue != null) {
            LOGGER.warn("[SSOptimizer] 命令消费者不是 RenderQueueImpl（"
                    + renderQueue.getClass().getName() + "），帧失败簿记自愈钩子未注册");
        }
    }

    static void uninstall() {
        if (queue instanceof RenderQueueImpl) {
            ((RenderQueueImpl) queue).setFrameFailureHook(null);
        }
        queue = null;
        CONTEXT_RECREATED_LISTENERS.clear();
        RECORDING_CONTEXT.remove();
        mainRecordingContext = null;
        executedArrayBufferBinding = 0;
        clientAttribDepth = 0;
        BIND_TRACE_SEQ.set(0);
        for (final BindTraceEvent event : BIND_TRACE) {
            event.seq = 0;
            event.buffer = 0;
            event.origin = null;
            event.recordThread = null;
        }
        arrayBufferBindingProbe =
                () -> org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        resetMainGlContext();
        frameSubmitSeq = 0;
        stateSnapshotSource = GlStateSnapshot::capture;
        syncOps = RealSyncOpsImpl.INSTANCE;
        auxResyncLogged = false;
        RenderQueueImpl.resetAuxSubmissionEpochForTesting();
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

    /** 提交前必须落帧挂起顶点流，避免本帧内容越过帧边界。 */
    static void swapFrames() {
        flushVertexStream();
        queue().swapFrames();
        frameSubmitSeq++;
        refreshMainRecordingContext();
    }

    static void swapFramesAndSync() {
        flushVertexStream();
        queue().swapFramesAndSync();
        frameSubmitSeq++;
        refreshMainRecordingContext();
    }

    /** 帧边界重置去重缓存，状态命令不得跨帧去重。 */
    private static void refreshMainRecordingContext() {
        if (RenderQueueImpl.isMainThread()) {
            RecordingContext ctx = RECORDING_CONTEXT.get();
            mainRecordingContext = ctx;
            ctx.dedupFrame = queue().currentFrame();
            ctx.stateDedup.invalidate();
        }
    }

    static void enqueue(GlCommand command) {
        enqueue(recordingContext(), command);
    }

    /** 命令须排在当前顶点流之后；aux 线程在自己的共享上下文执行。 */
    static void enqueue(RecordingContext ctx, GlCommand command) {
        flushVertexStream(ctx);
        if (ctx.auxNative) {
            command.execute();
            return;
        }
        // 包装 MergedBatchCommand 会打断渲染线程的串合并判定
        if (GL_ERROR_PROBE_COMMAND && !(command instanceof MergedBatchCommand)) {
            command = new ProbeSiteCommand(command);
        }
        queue().submit(command);
    }

    /** 仅跳过同一帧内相邻且参数相同的状态命令，任何状态改动插入都必须使去重失效。 */
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
        // 挂起顶点流会改变真实状态但不推进 commitSeq，必须使相邻性缓存失效
        if (ctx.vertexStream.hasPendingStateOps()) {
            dedup.invalidate();
        }
        if (dedup.shouldSkip(frame, type, a, b, c, d)) {
            return;
        }
        enqueue(command);
        dedup.record(frame, type, a, b, c, d);
    }

    static RecordingContext recordingContext() {
        RecordingContext cached = mainRecordingContext;
        if (cached != null && cached.owner == Thread.currentThread()) {
            return cached;
        }
        return RECORDING_CONTEXT.get();
    }

    static VertexStream vertexStream() {
        return recordingContext().vertexStream;
    }

    /** 将当前线程挂起顶点流按录制顺序移交渲染线程。 */
    static void flushVertexStream() {
        flushVertexStream(recordingContext());
    }

    static void flushVertexStreamOnGlEnd(RecordingContext ctx) {
        if (shouldFlushOnGlEnd(ctx)) {
            flushVertexStream(ctx);
        }
    }

    static boolean shouldFlushOnGlEnd(RecordingContext ctx) {
        return !deferredGlEndEnabled
                || ctx.auxNative
                || ctx.vertexStream.length() >= deferredGlEndThresholdBytes;
    }

    /** aux 原生线程不得挂起矩阵操作，否则后续内联回读会越过未回放命令。 */
    static boolean shouldStreamMatrixOps(RecordingContext ctx) {
        return streamMatrixOpsEnabled && !ctx.auxNative;
    }

    static void flushVertexStream(RecordingContext ctx) {
        VertexStream stream = ctx.vertexStream;
        if (stream.isEmpty()) {
            return;
        }
        int length = stream.length();
        // 跨批次开放的 begin/end 段必须逐指令回放，不能走数组合并
        final boolean startsOpen = ctx.vertexStreamStartsOpen;
        final boolean endsOpen = stream.hasOpenSegment();
        ctx.vertexStreamStartsOpen = endsOpen;
        byte[] data = stream.transferBuffer();
        if (ctx.auxNative) {
            // VertexArrayBatch 是渲染线程共享单例，aux 线程必须独立逐指令回放
            try {
                VertexStream.replayBody(data, length, ImmediateVertexSink.INSTANCE);
            } finally {
                releaseVertexStreamBuffer(data, ctx.returnedVertexBuffers);
            }
            return;
        }
        VertexBatchCommand batch = vertexBatches.acquire();
        batch.setData(data, length, startsOpen || endsOpen, ctx.returnedVertexBuffers);
        queue().submit(batch);
    }

    /** 非渲染线程先落帧并提交当前帧，保证 getter 读取此前全部命令执行后的状态。 */
    static <T> T blockingGet(Callable<T> getter) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            if (recordingContext().auxNative) {
                return callInline(getter);
            }
            flushVertexStream();
            swapFrames();
        }
        return q.get(getter);
    }

    private static <T> T callInline(Callable<T> getter) {
        try {
            return getter.call();
        } catch (Exception e) {
            throw new IllegalStateException("[SSOptimizer] aux 原生线程内联执行 GL 任务失败", e);
        }
    }

    static void blockingWait(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            if (recordingContext().auxNative) {
                task.run();
                return;
            }
            flushVertexStream();
            swapFrames();
        }
        q.wait(task);
    }

    static <T> T blockingGetResource(Callable<T> getter) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            if (recordingContext().auxNative) {
                return callInline(getter);
            }
            flushVertexStream();
            swapFrames();
        }
        return q.getUncounted(getter);
    }

    static void blockingWaitResource(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            if (recordingContext().auxNative) {
                task.run();
                return;
            }
            flushVertexStream();
            swapFrames();
        }
        q.waitUncounted(task);
    }

    /** fence 等待前提交当前帧；实际等待由调用线程执行，避免渲染线程自旋造成跨帧死锁。 */
    static void flushForFenceWait() {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            if (recordingContext().auxNative) {
                return;
            }
            flushVertexStream();
            swapFrames();
        }
    }

    /** stash 空时由渲染线程批量生成，id 唯一性由真实 glGenBuffers 保证。 */
    static int acquireBufferId() {
        if (recordingContext().auxNative) {
            return org.lwjgl.opengl.GL15.glGenBuffers();
        }
        Integer stashed = bufferIdStash.poll();
        if (stashed != null) {
            bufferIdStashCount.decrementAndGet();
            return stashed;
        }
        int[] generated = blockingGetResource(() -> {
            // 驱动按平台字节序写入 id，直接缓冲必须使用 nativeOrder
            IntBuffer ids = ByteBuffer.allocateDirect(BUFFER_ID_STASH_BATCH * 4)
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
            org.lwjgl.opengl.GL15.glGenBuffers(ids);
            int[] batch = new int[BUFFER_ID_STASH_BATCH];
            ids.get(batch);
            validateGeneratedBufferIds(batch, org.lwjgl.opengl.GL11::glGetError);
            return batch;
        });
        for (int i = 1; i < generated.length; i++) {
            bufferIdStash.add(generated[i]);
            bufferIdStashCount.incrementAndGet();
        }
        return generated[0];
    }

    static void refillBufferIdStashIfLow() {
        if (bufferIdStashCount.get() >= BUFFER_ID_STASH_LOW_WATER) {
            return;
        }
        int[] generated = new int[BUFFER_ID_STASH_BATCH];
        IntBuffer ids = ByteBuffer.allocateDirect(BUFFER_ID_STASH_BATCH * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        org.lwjgl.opengl.GL15.glGenBuffers(ids);
        ids.get(generated);
        validateGeneratedBufferIds(generated, org.lwjgl.opengl.GL11::glGetError);
        for (int id : generated) {
            bufferIdStash.add(id);
            bufferIdStashCount.incrementAndGet();
        }
    }

    /** LWJGL2 不因 GL 错误自动抛异常，必须拒绝分发驱动生成的无效 id。 */
    static void validateGeneratedBufferIds(int[] batch, IntSupplier glErrorSupplier) {
        int invalidCount = 0;
        int firstInvalidIndex = -1;
        for (int i = 0; i < batch.length; i++) {
            if (batch[i] >= 1) {
                continue;
            }
            if (firstInvalidIndex < 0) {
                firstInvalidIndex = i;
            }
            invalidCount++;
        }
        if (invalidCount == 0) {
            return;
        }
        throw new IllegalStateException(String.format(
                "[SSOptimizer] 真实 glGenBuffers 批发出 %d/%d 个无效 id（首个下标 %d，glGetError=0x%08X）。"
                        + "GL 上下文疑似异常（驱动故障或上下文丢失），拒绝分发死 id。",
                invalidCount, batch.length, firstInvalidIndex, glErrorSupplier.getAsInt()));
    }

    interface ThrowingTask {
        void run() throws LWJGLException;
    }

    interface ThrowingGetter<T> {
        T get() throws LWJGLException;
    }

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

    static ClientPointerState pointerState() {
        return recordingContext().pointerState;
    }

    static int framebufferBinding() {
        return recordingContext().framebufferBinding[0];
    }

    static void setFramebufferBinding(int framebuffer) {
        recordingContext().framebufferBinding[0] = framebuffer;
    }

    static SimulatedGlState simulatedState() {
        return recordingContext().simulatedState;
    }

    /**
     * aux 纪元变化时通过一次阻塞屏障回读完整状态，每个提交段至多再同步一次。
     * 主录制线程的仿真状态不包含 aux 队列提交。
     */
    static void resyncSimulatedStateIfAuxDirty() {
        final RecordingContext ctx = recordingContext();
        if (ctx.auxResyncSeq == frameSubmitSeq) {
            return;
        }
        final long epoch = RenderQueueImpl.auxSubmissionEpoch();
        if (ctx.auxStateEpoch == epoch) {
            return;
        }
        final GlStateSnapshot snapshot = blockingGetResource(stateSnapshotSource::get);
        ctx.simulatedState.adoptSnapshot(snapshot);
        ctx.framebufferBinding[0] = snapshot.framebufferBinding;
        ctx.auxStateEpoch = RenderQueueImpl.auxSubmissionEpoch();
        ctx.auxResyncSeq = frameSubmitSeq;
        if (!auxResyncLogged) {
            auxResyncLogged = true;
            LOGGER.info("[SSOptimizer] 检测到 aux 生产者线程 GL 提交（BoxUtil 后台线程等），"
                    + "主线程 getter 状态仿真进入屏障再同步模式：aux 活跃期每帧至多一次状态回读，"
                    + "纯主线程流程不受影响");
        }
    }

    static void stateSnapshotSourceForTesting(final java.util.function.Supplier<GlStateSnapshot> source) {
        stateSnapshotSource = source;
    }

    static RealSyncOps syncOps() {
        return syncOps;
    }

    static void syncOpsForTesting(final RealSyncOps ops) {
        syncOps = ops;
    }

    static Object mainGlContextToken() {
        return mainGlContextToken;
    }

    private static void resetMainGlContext() {
        UniformLookup.clear();
        displayListCompileDepth = 0;
        mainGlContextToken = new Object();
    }

    /** 真实主上下文创建成功后调用；窗口显示模式通知不调用此方法。 */
    static void onRenderThreadGlContextReady() {
        resetMainGlContext();
        if (!NativeRuntime.ensureQueueGlReady()) {
            LOGGER.warn("[SSOptimizer] 渲染线程 glad 未就绪，sync ops 保持 LWJGL 路径");
            return;
        }
        if (!NativeSyncOpsImpl.isSupported()) {
            LOGGER.warn("[SSOptimizer] 渲染线程驱动缺少 sync/memoryBarrier 函数指针，sync ops 保持 LWJGL 路径");
            return;
        }
        if (syncOps != NativeSyncOpsImpl.INSTANCE) {
            syncOps = NativeSyncOpsImpl.INSTANCE;
            LOGGER.info("[SSOptimizer] 渲染线程 glad 就绪：sync ops 切换 native 直调");
        }
    }

    /** 上下文重建后复位状态仿真并清空旧上下文的 VBO id stash，再通知全部监听器。 */
    static void onContextRecreated() {
        simulatedState().onContextRecreated();
        final RecordingContext ctx = recordingContext();
        ctx.framebufferBinding[0] = 0;
        ctx.auxStateEpoch = RenderQueueImpl.auxSubmissionEpoch();
        bufferIdStash = new ConcurrentLinkedQueue<>();
        bufferIdStashCount = new AtomicInteger();
        // 单个监听器失败不得阻止其他资源持有者清理旧上下文状态
        for (final Runnable listener : CONTEXT_RECREATED_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable t) {
                LOGGER.warn("[SSOptimizer] GL 上下文重建监听器执行失败，继续通知其余监听器", t);
            }
        }
    }

    static void registerContextRecreatedListener(final Runnable listener) {
        CONTEXT_RECREATED_LISTENERS.add(listener);
    }

    static RenderQueue installedQueue() {
        return queue;
    }

    /** aux 线程簿记不含主线程状态流，只有主录制线程可直接读取仿真 getter。 */
    static boolean isMainRecordingThread() {
        return RenderQueueImpl.isMainThread();
    }

    static int executedArrayBufferBinding() {
        return executedArrayBufferBinding;
    }

    /** 渲染线程 bind 命令更新绑定并记录溯源；净效果为零的恢复性绑定不得调用此方法。 */
    static void executedArrayBufferBinding(int buffer, String origin, String recordThread) {
        if (!isBookkeepingThread()) {
            return;
        }
        executedArrayBufferBinding = buffer;
        final long seq = BIND_TRACE_SEQ.incrementAndGet();
        final BindTraceEvent event = BIND_TRACE[(int) (seq & BIND_TRACE_MASK)];
        event.seq = seq;
        event.buffer = buffer;
        event.origin = origin;
        event.recordThread = recordThread;
    }

    /** 栈满时与真实 GL 一样保持簿记不变。 */
    static void onExecutedPushClientAttrib(int mask) {
        if (!isBookkeepingThread() || clientAttribDepth >= CLIENT_ATTRIB_STACK_MAX) {
            return;
        }
        clientAttribMasks[clientAttribDepth] = mask;
        clientAttribArrayBuffer[clientAttribDepth] = executedArrayBufferBinding;
        clientAttribDepth++;
    }

    /** 栈下溢时与真实 GL 一样保持簿记不变。 */
    static void onExecutedPopClientAttrib(String recordThread) {
        if (!isBookkeepingThread() || clientAttribDepth <= 0) {
            return;
        }
        final int mask = clientAttribMasks[--clientAttribDepth];
        if ((mask & org.lwjgl.opengl.GL11.GL_CLIENT_VERTEX_ARRAY_BIT) != 0) {
            executedArrayBufferBinding(clientAttribArrayBuffer[clientAttribDepth],
                    "GL11.glPopClientAttrib", recordThread);
        }
    }

    /**
     * 帧失败后回读真实绑定；不一致时输出溯源日志并校准簿记，避免后续 pointer 重放持续误判。
     *
     * @return 是否发生了簿记失真
     */
    static boolean reconcileArrayBufferBindingAfterFailure() {
        final int real = arrayBufferBindingProbe.getAsInt();
        final int booked = executedArrayBufferBinding;
        if (real == booked) {
            return false;
        }
        executedArrayBufferBinding = real;
        final StringBuilder dump = new StringBuilder(512);
        dump.append("[SSOptimizer] GL_ARRAY_BUFFER 簿记失真：簿记=").append(booked)
                .append(" 真实=").append(real)
                .append("（已校准簿记=真实值，防止后续帧级联失败）。最近绑定事件（新→旧）：");
        int emitted = 0;
        for (long seq = BIND_TRACE_SEQ.get(); seq >= 1 && emitted < 16; seq--, emitted++) {
            final BindTraceEvent event = BIND_TRACE[(int) (seq & BIND_TRACE_MASK)];
            if (event.seq != seq) {
                break; // 槽位被回绕覆写或从未写入
            }
            dump.append("\n  #").append(event.seq)
                    .append(" buffer=").append(event.buffer)
                    .append(" via=").append(event.origin)
                    .append(" recordedBy=").append(event.recordThread);
        }
        if (emitted == 0) {
            dump.append("（空）");
        }
        dump.append("\n  若事件环无法解释真实值 ").append(real)
                .append("，失真来源在 bridge 之外（native 直调 / 渲染线程直调 LWJGL），请携日志上报。");
        LOGGER.error(dump.toString());
        return true;
    }

    static void arrayBufferBindingProbeForTesting(final java.util.function.IntSupplier probe) {
        arrayBufferBindingProbe = probe;
    }

    static String latestBindTraceForTesting() {
        final long seq = BIND_TRACE_SEQ.get();
        if (seq == 0) {
            return null;
        }
        final BindTraceEvent event = BIND_TRACE[(int) (seq & BIND_TRACE_MASK)];
        if (event.seq != seq) {
            return null;
        }
        return event.seq + "|" + event.buffer + "|" + event.origin + "|" + event.recordThread;
    }

    static void onDisplayListCompileStart() {
        displayListCompileDepth++;
    }

    static void onDisplayListCompileEnd() {
        if (displayListCompileDepth <= 0) {
            if (displayListCompileDepth < 0) {
                LOGGER.warn("[SSOptimizer] 显示列表编译深度为负（" + displayListCompileDepth + "），已重置为 0");
            }
            displayListCompileDepth = 0;
            return;
        }
        displayListCompileDepth--;
    }

    static boolean isCompilingDisplayList() {
        return displayListCompileDepth > 0;
    }

    static BufferSnapshotPool pool() {
        return pool;
    }

    static DrawCommand acquireDrawCommand() {
        return drawCommands.acquire();
    }

    static void releaseDrawCommand(DrawCommand command) {
        drawCommands.release(command);
    }

    static PointerSnapshotGroup acquireSnapshotGroup() {
        return snapshotGroups.acquire();
    }

    static void releaseSnapshotGroup(PointerSnapshotGroup group) {
        snapshotGroups.release(group);
    }

    static void releaseVertexBatch(VertexBatchCommand batch) {
        vertexBatches.release(batch);
    }

    static byte[] acquireVertexStreamBuffer(int minCapacity,
                                            org.jctools.queues.SpscArrayQueue<byte[]> inbox) {
        return vertexStreamBuffers.acquire(minCapacity, inbox);
    }

    static VertexStreamBufferPool vertexStreamBufferPool() {
        return vertexStreamBuffers;
    }

    /** inbox 中的缓冲仍计为在途借出，只有进入全局池时才递减 inFlight。 */
    static void releaseVertexStreamBuffer(byte[] data,
                                          org.jctools.queues.SpscArrayQueue<byte[]> returnTo) {
        if (returnTo == null || !returnTo.offer(data)) {
            vertexStreamBuffers.release(data);
        }
    }

    static void resetPoolForTesting() {
        pool = new BufferSnapshotPoolImpl();
    }

    static BufferSnapshotPool snapshotPool() {
        return pool;
    }

    static void releaseSnapshot(ByteBuffer snapshot) {
        pool.release(snapshot);
    }

    interface SnapshotCommand {
        void execute(ByteBuffer snapshot);
    }

    /** 录制时深拷贝源 buffer，命令执行结束后无论成败都归还快照。 */
    static void enqueueSnapshot(ByteBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    static void enqueueSnapshot(DoubleBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    static void enqueueSnapshot(FloatBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

    static void enqueueSnapshot(IntBuffer src, SnapshotCommand command) {
        enqueueSnapshotCommand(pool.snapshot(src), command);
    }

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
