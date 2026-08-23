package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.MergedBatchCommand;
import github.kasuminova.ssoptimizer.common.render.queue.ProbeSiteCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
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
import java.util.function.IntSupplier;

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
    private static final Logger LOGGER = Logger.getLogger(BridgeSupport.class);

    /**
     * 状态命令去重开关：{@code -Dssoptimizer.render.statededup=false} 关闭，
     * 默认开启（连续相同的高频状态命令只入队一次，见 {@link StateDedup}）。
     * 静态可变字段供测试切换；uninstall 不重置（系统属性进程级语义）。
     */
    static volatile boolean stateDedupEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.statededup", "true"));

    /**
     * 命令级 GL 错误探针（仅诊断）：{@code glErrorProbe=command} 时 enqueue 把
     * 命令包装为 {@link ProbeSiteCommand} 并捕获录制点堆栈，供渲染线程侧探针
     * 在排空到错误时输出定位（bridge 命令体多为匿名 lambda，类型名不可读）。
     * 编译期常量属性名内联，不触发 RenderQueueImpl 类初始化。
     */
    private static final boolean GL_ERROR_PROBE_COMMAND = "command".equals(
            System.getProperty(RenderQueueImpl.GL_ERROR_PROBE_PROPERTY, "off"));

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
    /**
     * 渲染线程侧簿记：display list 编译窗口的嵌套深度（glNewList/glEndList 命令
     * 体在渲染线程执行序上增减）。GL 规范中 display list 编译对客户端数组
     * （glVertexPointer/glTexCoordPointer 等）按<b>指针捕获</b>、不回拷数据——
     * {@link VertexArrayBatch} 的共享单例直接缓冲跨批次复用，列表回放时
     * {@code glDrawArrays} 会读到后续批次覆盖的内容（实机症状：对话框舰队
     * 列表舰船图标串图）。编译窗口内的顶点批次因此必须逐指令 immediate 回放
     * （glBegin/glVertex/glEnd 按数据捕获，回放正确）。
     * <p>
     * 归渲染线程执行序：glNewList/glEndList 命令的入队与执行之间隔着帧边界
     * （命令在录制线程入队、渲染线程下一帧执行），录制侧维护会在入队序上
     * 提前改变状态；此处由命令执行体维护，命令流当前位置的编译深度恒准确，
     * 编译窗口跨帧也正确保持。
     */
    private static volatile int displayListCompileDepth;

    private static volatile RenderQueue queue;

    /**
     * 帧提交序号：{@link #swapFrames()}/{@link #swapFramesAndSync()} 收口递增
     * （含阻塞通道 drain-first 的帧切割）。用途：aux 再同步的「每提交段至多一次
     * 屏障」滞后判据——屏障内部的 swapFrames 自身推进序号，再同步完成后记录新
     * 序号，同段内后续仿真 getter 不再触发屏障（见
     * {@link #resyncSimulatedStateIfAuxDirty()}）。
     */
    private static volatile long frameSubmitSeq;
    /**
     * 渲染线程侧全量状态采样的来源（aux 再同步屏障的执行体）：生产为
     * {@link GlStateSnapshot#capture()}（真实 GL 回读）；单测替换为桩以避免无
     * 上下文环境触碰真实 GL（与 {@link RenderQueueImpl} 的 glErrorSource 注入桩同模式）。
     */
    private static volatile java.util.function.Supplier<GlStateSnapshot> stateSnapshotSource =
            GlStateSnapshot::capture;
    /** aux 再同步首次触发的一次性日志标记（诊断确认用，避免逐帧刷屏）。 */
    private static volatile boolean auxResyncLogged;

    /**
     * GL 上下文重建监听器（{@link GlDispatch#registerContextRecreatedListener(Runnable)}
     * 的注册落点）：onContextRecreated 末尾逐个通知。CopyOnWrite 语义——注册在启动期
     * 发生、通知在显示模式切换期发生，双方都不在帧热路径上，读多写极少。
     */
    private static final java.util.List<Runnable> CONTEXT_RECREATED_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

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
        CONTEXT_RECREATED_LISTENERS.clear();
        RECORDING_CONTEXT.remove();
        mainRecordingContext = null;
        executedArrayBufferBinding = 0;
        displayListCompileDepth = 0;
        frameSubmitSeq = 0;
        stateSnapshotSource = GlStateSnapshot::capture;
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

    /**
     * 帧提交收口（不等待）：swap 后刷新主录制线程的帧上下文缓存——
     * 主线程每帧此处获取一次 {@link RecordingContext}，帧内 GL 调用直读
     * 缓存，不再逐调用走 ThreadLocal（v36 profile 热点）。
     */
    static void swapFrames() {
        queue().swapFrames();
        frameSubmitSeq++;
        refreshMainRecordingContext();
    }

    /** 帧提交收口（等待上一帧完成，Display.update 的帧尾调用形态）。 */
    static void swapFramesAndSync() {
        queue().swapFramesAndSync();
        frameSubmitSeq++;
        refreshMainRecordingContext();
    }

    /**
     * 主录制线程（游戏主循环线程，首个 swapFramesAndSync 调用时认领，见
     * {@link RenderQueueImpl}）在帧边界缓存上下文与当前录制帧（状态命令去重的
     * 相邻性判据），并重置去重缓存——跨帧不延续去重（GL 状态虽跨帧保持，
     * 保守起见帧间状态命令照常入队）。
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
        // 探针包装跳过 MergedBatchCommand：包装会打断渲染线程侧的串合并
        // instance-of 判定，改变被诊断对象本身的执行形态
        if (GL_ERROR_PROBE_COMMAND && !(command instanceof MergedBatchCommand)) {
            command = new ProbeSiteCommand(command);
        }
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
        flushVertexStream(recordingContext());
    }

    /**
     * {@link #flushVertexStream()} 的上下文直传形式：调用点已持有本线程录制上下文
     * （如 {@code GL11#glEnd()}）时免去第二次 {@link #recordingContext()} 获取。
     */
    static void flushVertexStream(RecordingContext ctx) {
        VertexStream stream = ctx.vertexStream;
        if (stream.isEmpty()) {
            return;
        }
        int length = stream.length();
        // 开放段切割检测：本批次以段开放开始（上一批次未收口）或结束时段仍
        // 开放（非流式命令插入 begin..end 之间）→ 逐指令 immediate 兜底回放
        final boolean startsOpen = ctx.vertexStreamStartsOpen;
        final boolean endsOpen = stream.hasOpenSegment();
        ctx.vertexStreamStartsOpen = endsOpen;
        byte[] data = stream.transferBuffer();
        VertexBatchCommand batch = vertexBatches.acquire();
        batch.setData(data, length, startsOpen || endsOpen);
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
            // nativeOrder 是硬契约：驱动按平台小端序写入，默认大端视图会把
            // id 读成字节交换值（低字节 ≥0x80 时为负，BoxUtil 校验即死于斯）
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
        // nativeOrder 契约同 acquireBufferId
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

    /**
     * 真实 glGenBuffers 批发结果的 fail-fast 校验（渲染线程上、生成点就地调用）。
     * LWJGL2 不按 GL 错误抛异常：上下文异常/驱动故障时批发会静默写入 0，
     * 死 id 流入调用方后在模组侧以隐晦形式爆发（BoxUtil runtimeBufferIDCheck
     * 杀死逻辑线程），或成为幽灵绑定互踩渲染内容。此处拦截并附 GL 错误码。
     *
     * @param batch           批发出的 id 数组
     * @param glErrorSupplier 渲染线程上的 glGetError 取值（测试注入桩，避免无
     *                        上下文环境触碰真实 GL）
     */
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
     * 主录制线程仿真 getter 的 aux 活动失效检查（各 getter 仿真读之前调用；
     * 调用点保证当前线程是主录制线程）。
     * <p>
     * 动机：{@link SimulatedGlState} 只镜像主线程自己的命令流；aux 生产者线程
     * （BoxUtil 后台线程、字体预热 daemon 经 GlDispatch 等）经
     * {@code RenderQueueImpl.submit} 提交的命令会插入主线程正在录制的帧，在渲染
     * 线程上改变真实 GL 状态而不进主线程簿记（实机回归：ASTD TexTrailRenderer
     * 逐帧保存/恢复惯用法读过期的 CURRENT_PROGRAM/TEXTURE_BINDING_2D/矩阵仿真值，
     * finally 恢复错误状态——拖尾画坏并污染紧随的 HUD 字体绘制）。aux 提交由
     * {@code RenderQueueImpl.auxSubmissionEpoch()} 纪元标记；纪元变化即簿记不可信。
     * <p>
     * 失效语义：纪元脏时经一次阻塞屏障（资源通道，不计 StallDetector——每帧至多
     * 一次的有界再同步，与「模组逐帧轮询 getter」的打穿形态不同）在渲染线程批量
     * 读回全部跟踪状态并采入簿记（{@link SimulatedGlState#adoptSnapshot}），同时
     * 复位 FRAMEBUFFER 绑定跟踪。屏障的 drain-first 顺序保证采样点包含此前全部
     * 已提交命令（含 aux 命令）的执行结果；屏障后重读纪元，若期间 aux 又提交则
     * 下一检查点再同步（保守不错过）。
     * <p>
     * 性能分层：纯主线程流程 = 一次序号比较 + 一次纪元 volatile 读，零屏障；
     * aux 活跃期每提交段至多一次屏障（序号滞后窗口），同段内后续 getter 直接
     * 用再同步后的簿记。段中途 aux 新提交造成的段内残留污染属 SharedDrawable
     * 折叠模型的既定限制（见其类 javadoc），不在本机制职责内。
     */
    static void resyncSimulatedStateIfAuxDirty() {
        final RecordingContext ctx = recordingContext();
        // 滞后窗口只覆盖「已屏障再同步」的提交段：屏障是重操作，每段至多一次；
        // 干净路径不做序号短路——纪元 volatile 读代价可忽略，段中途 aux 开始
        // 活动必须被本段内下一个 getter 看见
        if (ctx.auxResyncSeq == frameSubmitSeq) {
            return;
        }
        final long epoch = RenderQueueImpl.auxSubmissionEpoch();
        if (ctx.auxStateEpoch == epoch) {
            // 无 aux 活动：纯主线程流程零屏障
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

    /** 测试用：替换 aux 再同步屏障的采样来源（无 GL 上下文环境注入桩）。 */
    static void stateSnapshotSourceForTesting(final java.util.function.Supplier<GlStateSnapshot> source) {
        stateSnapshotSource = source;
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
        // 新上下文全部状态回到默认值：仿真簿记归零即真值——FBO 绑定跟踪复位为 0，
        // aux 活动纪元标记为已同步（重建后簿记与真实状态一致，无需屏障再同步）
        final RecordingContext ctx = recordingContext();
        ctx.framebufferBinding[0] = 0;
        ctx.auxStateEpoch = RenderQueueImpl.auxSubmissionEpoch();
        bufferIdStash = new ConcurrentLinkedQueue<>();
        bufferIdStashCount = new AtomicInteger();
        // 通知外部资源持有者（如字体动态图集）：单个监听器抛错不中断其余监听器——
        // 上下文重建后所有持有者都必须有机会复位自身簿记，否则残留旧上下文的死 id
        for (final Runnable listener : CONTEXT_RECREATED_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable t) {
                LOGGER.warn("[SSOptimizer] GL 上下文重建监听器执行失败，继续通知其余监听器", t);
            }
        }
    }

    /** 注册 GL 上下文重建监听器（供 {@link GlDispatch} 门面转发）。 */
    static void registerContextRecreatedListener(final Runnable listener) {
        CONTEXT_RECREATED_LISTENERS.add(listener);
    }

    /** 已安装的队列（可为 null）；{@link GlDispatch#isRenderThread()} 的空安全判定用。 */
    static RenderQueue installedQueue() {
        return queue;
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

    /**
     * 渲染线程侧：进入 display list 编译窗口（glNewList 命令体调用）。
     * 嵌套计数：display list 编译期间的 glNewList（合法场景为嵌套列表编译，
     * 或异常序列）正确累加；窗口内深度恒 >0，跨帧保持。
     */
    static void onDisplayListCompileStart() {
        displayListCompileDepth++;
    }

    /**
     * 渲染线程侧：离开 display list 编译窗口（glEndList 命令体调用）。
     * 深度归零后渲染线程恢复数组化回放（编译窗口外行为完全不变）。
     */
    static void onDisplayListCompileEnd() {
        if (displayListCompileDepth <= 0) {
            // 防御：glEndList 命令体先于 glNewList 出现（非法 GL 序列或命令流被
            // 帧边界/悬挂切割的异常态）——深度已归零时不得继续递减（负数会让
            // 后续批次误判「编译中」恒真）。此路径不产生真实 GL 调用，仅簿记
            // 提前损坏，记 WARN 便于诊断命令流异常。
            if (displayListCompileDepth < 0) {
                LOGGER.warn("[SSOptimizer] display list 编译深度异常为负（" + displayListCompileDepth
                        + "），复位为 0——命令流中 glEndList 多于 glNewList，GL 序列疑似非法");
            }
            displayListCompileDepth = 0;
            return;
        }
        displayListCompileDepth--;
    }

    /** 渲染线程侧：当前命令流位置是否处于 display list 编译窗口内。 */
    static boolean isCompilingDisplayList() {
        return displayListCompileDepth > 0;
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
