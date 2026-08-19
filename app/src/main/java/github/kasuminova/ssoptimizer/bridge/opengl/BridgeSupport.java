package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.AuxOriginCommand;
import github.kasuminova.ssoptimizer.common.render.queue.AuxRunFence;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPool;
import github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl;
import github.kasuminova.ssoptimizer.common.render.queue.GlCommand;
import github.kasuminova.ssoptimizer.common.render.queue.RenderFrame;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.RenderSegment;
import org.apache.log4j.Logger;
import org.lwjgl.LWJGLException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
     * aux 命令来源标记开关：{@code -Dssoptimizer.render.auxfence=false} 时 aux
     * 线程命令不包装 {@link AuxOriginCommand}（执行侧围栏/编译窗口延迟随之失效），
     * 用于腐坏问题的 A/B 对照。诊断计数
     * （{@link RenderQueueImpl#auxCommandsSubmitted}）不受开关影响——标记关闭时
     * 仍统计 aux 录制量（区分「无 aux 活性」与「围栏未生效」）。
     */
    static volatile boolean auxFenceEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.auxfence", "true"));

    /**
     * aux 对象写入丢弃实验开关：{@code -Dssoptimizer.render.auxmutationdrop=true}
     * 时，aux 生产者线程（非主录制线程且无并行段绑定）提交的<b>对象内容写入类</b>
     * 命令（glTexImage/glTexSubImage/glBufferData(SubData) 各形态与 buffer 形态
     * delete 等全部快照命令，以及单值形态 delete）在录制侧直接丢弃（快照归还池、
     * 不产生命令），并对每个肇事调用点输出一次性 WARN。
     * <p>
     * 诊断目的：验证「aux 线程的对象写入因折叠上下文（无独立共享上下文）落在
     * 游戏当前绑定的纹理/VBO 上，覆写其内容」这一文本腐坏假设——围栏只隔离
     * 绘制时状态污染，管不了对象内容。默认关闭，关闭时零额外开销。
     */
    static volatile boolean auxMutationDrop =
            Boolean.getBoolean("ssoptimizer.render.auxmutationdrop");

    /** aux 写入丢弃实验的一次性 WARN 去重集（调用点字符串）。 */
    private static final Set<String> AUX_DROP_LOGGED = ConcurrentHashMap.newKeySet();

    /** 丢弃调用点定位用的 StackWalker（无类引用保留需求，默认配置即可）。 */
    private static final StackWalker DROP_SITE_WALKER = StackWalker.getInstance();

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
     * display list id stash 单次批量预生成的个数（真实 glGenLists(range) 返回
     * 连续区段基址，拆成 range 个独立 id 入池）。与 VBO stash 同构：
     * 字体字形/jitter 的显示列表惰性重建是战斗期高频路径，见
     * {@link DisplayListGuard}。
     */
    static final int LIST_ID_STASH_BATCH = 512;
    /** display list stash 低水位阈值：渲染线程帧尾补货触发线。 */
    static final int LIST_ID_STASH_LOW_WATER = 256;
    /**
     * display list id 预生成 stash：{@link DisplayListGuard#beginList()} 的
     * 录制侧 id 池。语义同 {@link #bufferIdStash}——stash 命中零阻塞，
     * 主线程空时一次阻塞批量预生成摊销往返；并行录制段内空时返回 -1
     * （段内禁止阻塞，调用方按 suspend 等价语义退化为直接渲染）。
     */
    private static volatile ConcurrentLinkedQueue<Integer> listIdStash = new ConcurrentLinkedQueue<>();
    /** display list stash 当前元素计数（与 stash 内容一致）。 */
    private static volatile AtomicInteger listIdStashCount = new AtomicInteger();
    /**
     * 纹理 id stash 单次批量预生成的个数。惰性纹理上传（LazyTextureManager
     * ensureTextureReady→glGenTextures）在战斗/标题渲染期随首次引用触发，
     * 并行录制段内禁止阻塞取 id——stash 命中是段内唯一合法路径。
     */
    static final int TEXTURE_ID_STASH_BATCH = 512;
    /** 纹理 stash 低水位阈值：渲染线程帧尾补货触发线。 */
    static final int TEXTURE_ID_STASH_LOW_WATER = 256;
    /**
     * 纹理 id 预生成 stash：{@link GL11#glGenTextures()} 的录制侧 id 池。
     * 语义同 {@link #bufferIdStash}——stash 命中零阻塞，空时一次阻塞批量
     * 预生成摊销往返；并行录制段内空时经阻塞通道 fail-fast（纹理 id 无
     * display list 那样的 suspend 退化路径，耗尽属 stash 容量缺口，必须
     * 显式暴露而非分发死 id）。
     */
    private static volatile ConcurrentLinkedQueue<Integer> textureIdStash = new ConcurrentLinkedQueue<>();
    /** 纹理 stash 当前元素计数（与 stash 内容一致）。 */
    private static volatile AtomicInteger textureIdStashCount = new AtomicInteger();
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
        listIdStash = new ConcurrentLinkedQueue<>();
        listIdStashCount = new AtomicInteger();
        textureIdStash = new ConcurrentLinkedQueue<>();
        textureIdStashCount = new AtomicInteger();
        DisplayListGuard.reset();
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
            ctx.dedupSegment = queue().currentFrame().serialSegment();
            ctx.stateDedup.invalidate();
        }
    }

    /**
     * 开启当前帧的下一个串行段（并行区屏障后由编排器在主线程调用）：
     * 帧内登记新段、切换主线程去重判据来源并失效段边界缓存（段首状态命令
     * 强制入队，跨段不去重，保守）。
     *
     * @return 新的当前串行段
     */
    static RenderSegment openNextSerialSegment() {
        RenderSegment segment = queue().currentFrame().openNextSerialSegment();
        RecordingContext ctx = recordingContext();
        ctx.dedupSegment = segment;
        ctx.stateDedup.invalidate();
        return segment;
    }

    /**
     * 把当前线程绑定到编排器指派的并行段（worker 段任务开始时调用）：此后
     * 本线程的一切录制（含顶点流落帧与状态命令）绕过帧临界区直接写入该段
     * （单写者契约，见 {@link RenderSegment}）。段任务结束必须
     * {@link #unbindSegment()}（try/finally）；绑定期间禁止阻塞式调用
     * （见 {@link #blockingGet(Callable)} 的 fail-fast）。
     *
     * @param segment 当前帧内经 {@code RenderFrame#reserveSegments} 预定的段
     */
    static void bindSegment(RenderSegment segment) {
        if (segment == null) {
            throw new IllegalArgumentException("segment must not be null");
        }
        RecordingContext ctx = RECORDING_CONTEXT.get();
        ctx.boundSegment = segment;
        ctx.dedupSegment = segment;
        ctx.stateDedup.invalidate();
    }

    /**
     * 解除当前线程的并行段绑定（段边界去重缓存失效，保守）。
     * 解绑前先把本线程未落帧的顶点流 flush 进当前段——worker 池线程跨任务复用
     * 同一 RecordingContext/VertexStream，残留流段若不解绑时落帧，会被下一个
     * 任务的首条命令 flush 进另一个段的段首，造成跨段状态/几何串扰。
     */
    static void unbindSegment() {
        RecordingContext ctx = RECORDING_CONTEXT.get();
        flushVertexStream(ctx);
        ctx.boundSegment = null;
        ctx.dedupSegment = null;
        ctx.stateDedup.invalidate();
    }

    /**
     * 录制一条命令到当前帧。若当前线程的顶点流有未落帧的 immediate 操作，
     * 先把它打包落帧——流段命令与本命令在帧列表中的顺序即录制顺序。
     * 并行段绑定期间（编排器指派的 worker）直接写入绑定段，绕过帧临界区。
     * <p>
     * aux-context 生产者线程（非主录制线程且无并行段绑定：BoxUtil 后台线程等
     * 模组自有线程）的命令包装为 {@link AuxOriginCommand}——渲染线程执行循环
     * 据此把连续 aux 命令围进状态围栏（{@link AuxRunFence}），隔离其对游戏侧
     * GL 状态的污染（文本腐坏根因，见 docs/design/render-parallel-recording.md）。
     */
    static void enqueue(GlCommand command) {
        RecordingContext ctx = recordingContext();
        flushVertexStream(ctx);
        RenderSegment bound = ctx.boundSegment;
        if (bound != null) {
            bound.add(command);
        } else if (isAuxProducer(ctx)) {
            RenderQueueImpl.auxCommandsSubmitted.incrementAndGet();
            queue().submit(auxFenceEnabled ? new AuxOriginCommand(command) : command);
        } else {
            queue().submit(command);
        }
    }

    /**
     * 当前线程是否为 aux-context 生产者：非主录制线程、且无编排器指派的并行段
     * 绑定（worker 段任务写入绑定段，录制边界由编排器屏障保证，不走围栏）。
     */
    private static boolean isAuxProducer(RecordingContext ctx) {
        return ctx.boundSegment == null && !RenderQueueImpl.isMainThread();
    }

    /**
     * 录制一条可去重的状态命令（glBindTexture/glEnable/glDisable/glBlendFunc
     * 等，见 {@link StateDedup} 的类型常量）：与上一条已入队的状态命令
     * 类型参数完全相同、且期间本段命令列表无任何插入时跳过（不产生命令）；
     * 否则按 {@link #enqueue(GlCommand)} 落帧并记录指纹。段内任何插入——含
     * glCallList（显示列表执行绕过录制侧）、顶点流落帧、其他命令——都会经
     * 段提交序号（{@code RenderSegment.commitSeq}）打断相邻性；其他段的并发
     * 录制与本段回放时的执行相邻性无关，不打断。段边界（帧边界/段切换/worker
     * 绑定解绑）由调用路径失效重置，保证去重永不跨越「状态可能已被改变」的
     * 边界（旁路审计见 docs/design/render-state-dedup.md）。
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
        if (isAuxProducer(ctx)) {
            // aux 生产者：去重的相邻性判据（段提交序号）基于本线程视角的段，
            // aux 命令实际落入的串行段被主线程并发录制，判据失真；且 aux 命令
            // 由执行侧围栏隔离，去重省下的命令开销相对围栏 enter/exit 无意义。
            // 直接落帧，包装在 enqueue 内完成。
            enqueue(command);
            return;
        }
        RenderSegment segment = ctx.boundSegment;
        if (segment == null) {
            segment = ctx.dedupSegment;
            if (segment == null) {
                // 主录制线程首次 swap 前的启动窗口（帧边界缓存尚未建立）：
                // 现取当前帧的串行段
                segment = queue().currentFrame().serialSegment();
            }
        }
        StateDedup dedup = ctx.stateDedup;
        if (dedup.shouldSkip(segment, type, a, b, c, d)) {
            return;
        }
        enqueue(command);
        dedup.record(segment, type, a, b, c, d);
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
     * {@link #flushVertexStream()} 的上下文直传形式（热路径免去重复的
     * ThreadLocal/缓存查找）。并行段绑定期间批量命令直接写入绑定段，
     * 保持与后续命令在同一段内的录制顺序。
     */
    private static void flushVertexStream(RecordingContext ctx) {
        VertexStream stream = ctx.vertexStream;
        if (stream.isEmpty()) {
            return;
        }
        int length = stream.length();
        byte[] data = stream.transferBuffer();
        VertexBatchCommand batch = vertexBatches.acquire();
        batch.setData(data, length);
        RenderSegment bound = ctx.boundSegment;
        if (bound != null) {
            bound.add(batch);
        } else if (isAuxProducer(ctx)) {
            RenderQueueImpl.auxCommandsSubmitted.incrementAndGet();
            queue().submit(auxFenceEnabled ? new AuxOriginCommand(batch) : batch);
        } else {
            queue().submit(batch);
        }
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
            rejectBlockingInBoundSegment();
            // drain-first 必须包含未落帧的顶点流：getter 读到的是此前全部录制命令
            // 执行完的 GL 状态，顶点流是其中一部分
            flushVertexStream();
            swapFrames();
        }
        return q.get(getter);
    }

    /**
     * 并行段内阻塞通道 fail-fast：getter/同步任务/资源申请的 drain-first 会
     * swap 当前帧并等待渲染线程排空——段任务期间这会击穿分段不变量（帧被提前
     * 提交、worker 迟到写入已封存段），且所有 worker 随排空串行化，并行收益
     * 归零。并行段内的状态查询必须走 {@link SimulatedGlState} 仿真；未覆盖的
     * 查询属实现缺口，应补仿真而非放行阻塞。
     */
    private static void rejectBlockingInBoundSegment() {
        if (recordingContext().boundSegment != null) {
            throw new IllegalStateException(
                    "[SSOptimizer] 并行录制段内禁止阻塞式 GL 调用（getter/同步/资源申请会 drain 整条管线并击穿分段不变量）；"
                            + "请为该调用补状态仿真（SimulatedGlState）或将该调用点移出并行段");
        }
    }

    /** {@link #blockingGet(Callable)} 的无返回值形式。 */
    static void blockingWait(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            rejectBlockingInBoundSegment();
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
            rejectBlockingInBoundSegment();
            flushVertexStream();
            swapFrames();
        }
        return q.getUncounted(getter);
    }

    /** {@link #blockingGetResource(Callable)} 的无返回值形式。 */
    static void blockingWaitResource(Runnable task) {
        RenderQueue q = queue();
        if (!q.isRenderThread()) {
            rejectBlockingInBoundSegment();
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
     * 取一个 display list id（{@link DisplayListGuard#beginList()} 的入口）：
     * stash 非空零阻塞出队；空时——主线程/加载期走一次资源申请阻塞通道批量
     * 预生成 {@value #LIST_ID_STASH_BATCH} 个（真实 glGenLists 返回连续区段
     * 基址，拆成单个 id 入池，返回首个）；并行录制段内禁止阻塞，返回 -1，
     * 由调用方按 suspend 等价语义退化（display list 缓存未命中→直接渲染，
     * 调用方惯用法自带该回退路径）。
     *
     * @return 可用的 display list id，或 -1 表示段内 stash 耗尽
     */
    static int acquireListId() {
        Integer stashed = listIdStash.poll();
        if (stashed != null) {
            listIdStashCount.decrementAndGet();
            return stashed;
        }
        if (recordingContext().boundSegment != null) {
            return -1;
        }
        int[] generated = blockingGetResource(() -> {
            int base = org.lwjgl.opengl.GL11.glGenLists(LIST_ID_STASH_BATCH);
            if (base == 0) {
                throw new IllegalStateException(
                        "[SSOptimizer] 真实 glGenLists 批量预生成失败（返回 0，GL 上下文疑似异常）");
            }
            int[] batch = new int[LIST_ID_STASH_BATCH];
            for (int i = 0; i < batch.length; i++) {
                batch[i] = base + i;
            }
            return batch;
        });
        for (int i = 1; i < generated.length; i++) {
            listIdStash.add(generated[i]);
            listIdStashCount.incrementAndGet();
        }
        return generated[0];
    }

    /**
     * display list stash 低水位补货（渲染线程调用，与
     * {@link #refillBufferIdStashIfLow()} 同在 Display.update 命令体前置）：
     * 计数低于 {@value #LIST_ID_STASH_LOW_WATER} 时直接在渲染线程真实
     * glGenLists 一批 {@value #LIST_ID_STASH_BATCH} 个入 stash。
     */
    static void refillListIdStashIfLow() {
        if (listIdStashCount.get() >= LIST_ID_STASH_LOW_WATER) {
            return;
        }
        int base = org.lwjgl.opengl.GL11.glGenLists(LIST_ID_STASH_BATCH);
        if (base == 0) {
            throw new IllegalStateException(
                    "[SSOptimizer] 渲染线程 display list stash 补货失败（glGenLists 返回 0，GL 上下文疑似异常）");
        }
        for (int i = 0; i < LIST_ID_STASH_BATCH; i++) {
            listIdStash.add(base + i);
            listIdStashCount.incrementAndGet();
        }
    }

    /**
     * 取一个纹理 id（{@link GL11#glGenTextures()} 单值形式的入口）：stash 非空
     * 零阻塞出队；空则走一次资源申请阻塞通道，由渲染线程批量预生成
     * {@value #TEXTURE_ID_STASH_BATCH} 个 id——返回首个，其余入池。语义同
     * {@link #acquireBufferId()}；与 display list 不同，纹理 id 没有 suspend
     * 等价退化路径，段内 stash 耗尽只能经阻塞通道 fail-fast（容量缺口必须
     * 显式暴露）。稳态下由渲染线程每帧帧尾 {@link #refillTextureIdStashIfLow()}
     * 低水位补货保持 stash 恒有货。
     */
    static int acquireTextureId() {
        Integer stashed = textureIdStash.poll();
        if (stashed != null) {
            textureIdStashCount.decrementAndGet();
            return stashed;
        }
        int[] generated = blockingGetResource(() -> {
            // nativeOrder 契约同 acquireBufferId
            IntBuffer ids = ByteBuffer.allocateDirect(TEXTURE_ID_STASH_BATCH * 4)
                    .order(ByteOrder.nativeOrder()).asIntBuffer();
            org.lwjgl.opengl.GL11.glGenTextures(ids);
            int[] batch = new int[TEXTURE_ID_STASH_BATCH];
            ids.get(batch);
            validateGeneratedTextureIds(batch, org.lwjgl.opengl.GL11::glGetError);
            return batch;
        });
        for (int i = 1; i < generated.length; i++) {
            textureIdStash.add(generated[i]);
            textureIdStashCount.incrementAndGet();
        }
        return generated[0];
    }

    /**
     * 纹理 stash 低水位补货（渲染线程调用，与
     * {@link #refillBufferIdStashIfLow()} 同在 Display.update 命令体前置）：
     * 计数低于 {@value #TEXTURE_ID_STASH_LOW_WATER} 时直接在渲染线程真实
     * glGenTextures 一批 {@value #TEXTURE_ID_STASH_BATCH} 个入 stash。
     */
    static void refillTextureIdStashIfLow() {
        if (textureIdStashCount.get() >= TEXTURE_ID_STASH_LOW_WATER) {
            return;
        }
        int[] generated = new int[TEXTURE_ID_STASH_BATCH];
        // nativeOrder 契约同 acquireTextureId
        IntBuffer ids = ByteBuffer.allocateDirect(TEXTURE_ID_STASH_BATCH * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        org.lwjgl.opengl.GL11.glGenTextures(ids);
        ids.get(generated);
        validateGeneratedTextureIds(generated, org.lwjgl.opengl.GL11::glGetError);
        for (int id : generated) {
            textureIdStash.add(id);
            textureIdStashCount.incrementAndGet();
        }
    }

    /**
     * {@link GL11#glGenTextures(IntBuffer)} 的零阻塞填充尝试：stash 存量足够
     * 时出队 remaining 个 id 写入 out（推进 position）并返回 true；存量不足
     * 或竞争中耗尽时回滚已出队元素并返回 false，由调用方走阻塞通道兜底。
     * 并行录制段内存量不足经阻塞通道 fail-fast（同 {@link #acquireTextureId()}）。
     */
    static boolean tryFillTextureIds(IntBuffer out) {
        int n = out.remaining();
        if (n <= 0) {
            return true;
        }
        Integer[] ids = new Integer[n];
        for (int i = 0; i < n; i++) {
            ids[i] = textureIdStash.poll();
            if (ids[i] == null) {
                // 竞争中耗尽：已出队的放回池尾，一个 id 都不分发（调用方走阻塞通道全量取）
                for (int j = 0; j < i; j++) {
                    textureIdStash.add(ids[j]);
                    textureIdStashCount.incrementAndGet();
                }
                return false;
            }
            textureIdStashCount.decrementAndGet();
        }
        for (Integer id : ids) {
            out.put(id);
        }
        return true;
    }

    /**
     * 真实 glGenTextures 批发结果的 fail-fast 校验：动机同
     * {@link #validateGeneratedBufferIds}——LWJGL2 不按 GL 错误抛异常，
     * 批发静默出 0 时死 id 会以幽灵纹理形式爆发。此处拦截并附 GL 错误码。
     *
     * @param batch           批发出的 id 数组
     * @param glErrorSupplier 渲染线程上的 glGetError 取值（测试注入桩，避免无
     *                        上下文环境触碰真实 GL）
     */
    static void validateGeneratedTextureIds(int[] batch, IntSupplier glErrorSupplier) {
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
                "[SSOptimizer] 真实 glGenTextures 批发出 %d/%d 个无效 id（首个下标 %d，glGetError=0x%08X）。"
                        + "GL 上下文疑似异常（驱动故障或上下文丢失），拒绝分发死 id。",
                invalidCount, batch.length, firstInvalidIndex, glErrorSupplier.getAsInt()));
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
     * GL 上下文重建后的聚合簿记复位（Display.create/setDisplayMode/setFullscreen 成功
     * 后由主线程调用）：录制侧状态仿真归零（{@link SimulatedGlState#onContextRecreated()}），
     * 并清空 VBO id stash——stash 内预生成的 id 全部属于已销毁的旧上下文，
     * 继续分发出去的都是死 id。display list stash 同理（display list 本体
     * 随上下文销毁，簿记经 {@link DisplayListGuard#onContextRecreated()} 全量
     * 作废）。清空后由渲染线程帧尾
     * {@link #refillBufferIdStashIfLow()} 在新上下文里重新补货。
     */
    static void onContextRecreated() {
        simulatedState().onContextRecreated();
        bufferIdStash = new ConcurrentLinkedQueue<>();
        bufferIdStashCount = new AtomicInteger();
        listIdStash = new ConcurrentLinkedQueue<>();
        listIdStashCount = new AtomicInteger();
        textureIdStash = new ConcurrentLinkedQueue<>();
        textureIdStashCount = new AtomicInteger();
        DisplayListGuard.onContextRecreated();
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
        if (auxMutationDrop && isAuxProducer(recordingContext())) {
            // aux 对象写入丢弃实验（见 auxMutationDrop 字段注释）：快照归还池、
            // 不产生命令。快照形态覆盖了 aux 线程全部对象内容写入路径
            // （glTexImage*/glTexSubImage*/glBufferData(SubData)/buffer 形态 delete）。
            pool.release(snapshot);
            logAuxMutationDrop("snapshot-command");
            return;
        }
        // 录制线程名（getName 返回构造期 String，无分配）：执行期契约失败时的定位信息
        String recordThread = Thread.currentThread().getName();
        enqueue(() -> {
            try {
                command.execute(snapshot);
            } catch (IllegalArgumentException contractViolation) {
                // LWJGL BufferChecks 的契约校验（buffer 尺寸/类型不匹配）在原生调用
                // 之前抛出，GL 状态未被改写。原版中该异常抛在调用线程（模组自有线程
                // 通常自带 catch-all 继续运行）；bridge 把抛点移到了渲染线程，若按
                // 帧级 fail-fast 处理会把模组局部 bug 放大为全局崩溃。降级为命令级
                // 跳过并输出 ERROR（含录制线程名供定位肇事方）。
                LOGGER.error("[SSOptimizer] 快照命令因调用方 buffer 契约违例被跳过（录制线程 "
                        + recordThread + "）：" + contractViolation.getMessage(), contractViolation);
            } finally {
                pool.release(snapshot);
            }
        });
    }

    /**
     * aux 对象写入丢弃守卫（单值形态命令用：glDeleteTextures(int)/
     * glDeleteBuffers(int)/glDeleteLists/glDeleteProgram/glDeleteShader/
     * glDeleteFramebuffers(int)/glDeleteRenderbuffers(int)/glDeleteVertexArrays
     * 等方法体开头调用）：返回 true 表示本次调用应直接丢弃（无快照可归还）。
     * 快照形态在 {@link #enqueueSnapshotCommand} 入口统一处理。见
     * {@link #auxMutationDrop} 字段注释。开关关闭时仅一次 volatile 读。
     */
    static boolean dropAuxMutation(String callSite) {
        if (!auxMutationDrop) {
            return false;
        }
        if (!isAuxProducer(recordingContext())) {
            return false;
        }
        logAuxMutationDrop(callSite);
        return true;
    }

    /** 对每个肇事调用点输出一次性 WARN（含录制线程名），供实验判读定位。 */
    private static void logAuxMutationDrop(String callSite) {
        String site = callSite + " @ " + DROP_SITE_WALKER.walk(frames -> frames
                .filter(f -> {
                    String cls = f.getClassName();
                    return !cls.startsWith("github.kasuminova.ssoptimizer.common.render.queue.")
                            && !cls.startsWith("github.kasuminova.ssoptimizer.bridge.opengl.");
                })
                .findFirst()
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
        if (AUX_DROP_LOGGED.add(site)) {
            LOGGER.warn("[SSOptimizer] aux 对象写入已丢弃（auxmutationdrop 实验，线程 "
                    + Thread.currentThread().getName() + "）：" + site);
        }
    }
}
