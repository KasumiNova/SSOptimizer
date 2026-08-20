package github.kasuminova.ssoptimizer.common.render.queue;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.jctools.queues.MpscBlockingConsumerArrayQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;

/**
 * {@link RenderQueue} 的默认实现。
 * <p>
 * 线程模型：单渲染线程（daemon，{@value #RENDER_THREAD_NAME}）从提交队列取任务
 * 执行。任务分两类：帧任务（执行整帧命令，完成后完成帧 Future 并把帧归还
 * {@link FramePool}）与同步任务（{@link #get}/{@link #wait} 的阻塞式调用，
 * 结果经 CompletableFuture 返回调用方）。
 * <p>
 * 失败传播：渲染线程上帧命令抛异常时记日志、中止本帧剩余命令（GL 状态已不可信），
 * 异常随帧 Future 在下一次 {@link #swapFramesAndSync()} 等待上一帧时向主线程重抛
 * （语义参考 common/combat/ai/AiParallelExecutorImpl 的失败收集模式，载体从
 * 失败列表换成帧 Future）。
 * <p>
 * 并发要点：{@link #frameLock} 只保护「当前帧指针 + 提交」这一小段临界区，
 * 保证 aux-context 生产者线程的 {@link #submit(GlCommand)} 与主线程 swap 不会把
 * 命令丢进已提交的帧；帧内列表本身的并发追加由 {@link RenderFrame#add} 的
 * synchronized 兜底。
 * <p>
 * 帧悬挂协议：{@link WaitFenceCommand} 发现 fence 未 signal 时抛
 * {@link SuspendFrameException}，渲染线程不阻塞——剩余命令打包成续跑任务
 * requeue 到队尾，本帧正常完成释放主线程（否则 BoxUtil 的 Phaser 协调会与
 * 「main 等帧完成」形成三方死锁）；fence 信号到达后续跑任务会合执行余下命令。
 */
public final class RenderQueueImpl implements RenderQueue {
    /** 渲染线程名，profiler / 日志诊断用。 */
    public static final String RENDER_THREAD_NAME = "SSOptimizer-Render";

    /** 帧悬挂续跑任务 requeue 前的退避间隔（纳秒），避免 fence 长期未 signal 时续跑任务空转占满渲染线程。 */
    private static final long SUSPEND_REQUEUE_BACKOFF_NANOS = 1_000_000L;

    /** 提交队列容量（见字段注释：正常深度为个位数到几十，打满即渲染线程已死）。 */
    private static final int SUBMISSION_QUEUE_CAPACITY = 65536;

    /** 阻塞调用站点定位用的 StackWalker（无类引用保留需求，默认配置即可）。 */
    private static final StackWalker SITE_WALKER = StackWalker.getInstance();

    /** GL 错误探针系统属性：off（默认）/ frame（帧尾排空）/ command（逐命令排空）。 */
    public static final String GL_ERROR_PROBE_PROPERTY = "ssoptimizer.renderthread.glErrorProbe";

    private static final Logger LOGGER = Logger.getLogger(RenderQueueImpl.class);

    /**
     * 主录制线程：构造本队列的线程（游戏中即游戏主线程，mod 加载期构造）。
     * bridge 的帧录制上下文帧边界缓存（{@link BridgeSupport#recordingContext()}）
     * 依赖此判定——aux-context 生产者线程不得命中主线程缓存。
     */
    private static final Thread MAIN_THREAD = Thread.currentThread();

    private final FramePool framePool;
    private final StallDetector stallDetector;
    /**
     * 提交队列：MPSC 无锁有界数组队列（JCTools，项目既有依赖）。生产者为主线程
     * （帧/同步任务）、aux-context 生产者线程（同步任务）与渲染线程自身（悬挂
     * 续跑 requeue），唯一消费者是渲染线程——语义恰好 MPSC；相对
     * LinkedBlockingQueue 消除了每次 offer/take 的双锁与 Node 分配。
     * 容量取 {@value #SUBMISSION_QUEUE_CAPACITY}：稳态深度为「在飞帧任务（≤2）
     * + 阻塞中的同步任务（≈阻塞线程数）+ 在飞续跑任务」，正常为个位数到几十，
     * 该容量下的 offer 失败只可能意味着渲染线程已死，属必须暴露的不变量破坏。
     */
    private final MpscBlockingConsumerArrayQueue<RenderTask> submissionQueue =
            new MpscBlockingConsumerArrayQueue<>(SUBMISSION_QUEUE_CAPACITY);
    /** 阻塞式调用的来源站点计数（进程生命周期累计，熔断异常时输出 top 供定位；站点基数有界）。 */
    private final ConcurrentHashMap<String, LongAdder> blockingSites = new ConcurrentHashMap<>();
    private final Object frameLock = new Object();
    private final Thread renderThread;

    /** 当前录制帧；仅主线程 swap 时更换。 */
    private RenderFrame currentFrame;
    /**
     * 最近一次提交帧的完成 Future；swapFramesAndSync 等待的是它的前一帧。
     * 必须在提交（offer）之前从帧上捕获——帧执行完归还池后 reset 会换发新 Future，
     * 持有帧引用事后现读会等到下一周期的 Future 而永远阻塞。
     */
    private CompletableFuture<Void> lastSubmittedCompletion;

    private volatile boolean running = true;

    /** GL 错误探针模式（诊断设施，见 {@link #GL_ERROR_PROBE_PROPERTY}）。 */
    private final GlErrorProbe glErrorProbe;
    /** 探针取错误的来源（测试注入桩，避免无 context 环境下触真 GL；与 BridgeSupport 的注入桩同模式）。 */
    private final IntSupplier glErrorSource;
    /** 探针帧序号（仅诊断日志展示用）。 */
    private long probeFrameSequence;
    /** 已输出过录制点堆栈的站点指纹（前 6 帧拼串），仅渲染线程访问。 */
    private final java.util.Set<String> probeSiteKeys = new java.util.HashSet<>();

    public RenderQueueImpl() {
        this(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector());
    }

    /**
     * @param framePool     帧池
     * @param stallDetector 阻塞式调用熔断器（仅统计资源加载期结束——
     *                      RenderThreadMode.isLoadingFinished()——之后的
     *                      get/wait 阻塞调用；加载期成批一次性分配豁免）
     */
    public RenderQueueImpl(FramePool framePool, StallDetector stallDetector) {
        this(framePool, stallDetector,
                GlErrorProbe.parse(System.getProperty(GL_ERROR_PROBE_PROPERTY, "off")),
                // 上下文创建前渲染线程无 current context，glGetError 会抛
                // 「No OpenGL context」——未创建期视为无错误跳过（此处引用会被
                // RenderThreadRedirector 改写为 bridge 镜像，语义不变）
                () -> org.lwjgl.opengl.Display.isCreated() ? org.lwjgl.opengl.GL11.glGetError() : 0);
    }

    /** 测试入口：注入探针模式与错误来源桩。 */
    RenderQueueImpl(FramePool framePool, StallDetector stallDetector,
                    GlErrorProbe glErrorProbe, IntSupplier glErrorSource) {
        this.framePool = framePool;
        this.stallDetector = stallDetector;
        this.glErrorProbe = glErrorProbe;
        this.glErrorSource = glErrorSource;
        this.currentFrame = framePool.acquire();
        this.renderThread = new Thread(this::renderLoop, RENDER_THREAD_NAME);
        this.renderThread.setDaemon(true);
        this.renderThread.start();
    }

    @Override
    public RenderFrame currentFrame() {
        synchronized (frameLock) {
            return currentFrame;
        }
    }

    @Override
    public void submit(GlCommand command) {
        synchronized (frameLock) {
            // frameLock 已提供互斥与可见性，帧自身监视器在此冗余（addUnlocked）
            currentFrame.addUnlocked(command);
        }
    }

    @Override
    public void swapFrames() {
        synchronized (frameLock) {
            submitCurrentFrameLocked();
        }
        // 注意：这里不推进 StallDetector 窗口——drain-first 阻塞通道每次调用都伴随
        // 一次 swapFrames，窗口若以它前进会被「清下一槽」语义洗白/失真；
        // 游戏帧边界由 swapFramesAndSync 唯一标记（见 get 的熔断门控注释）
    }

    @Override
    public void swapFramesAndSync() {
        CompletableFuture<Void> previousCompletion;
        synchronized (frameLock) {
            previousCompletion = lastSubmittedCompletion;
            submitCurrentFrameLocked();
        }
        stallDetector.onSwap();
        if (previousCompletion != null) {
            awaitCompletion(previousCompletion);
        }
    }

    /**
     * 记录一次阻塞式调用的来源站点：取栈上第一个不属于队列/bridge 包的帧
     * （即游戏/模组/功能代码调用 bridge GL 方法的位置）。开销仅在已确定要
     * 走阻塞通道的调用上发生，相对队列往返可忽略。
     */
    private void recordBlockingSite() {
        String site = SITE_WALKER.walk(frames -> frames
                .filter(f -> {
                    String cls = f.getClassName();
                    return !cls.startsWith("github.kasuminova.ssoptimizer.common.render.queue.")
                            && !cls.startsWith("github.kasuminova.ssoptimizer.bridge.opengl.");
                })
                .findFirst()
                .map(f -> f.getClassName() + "." + f.getMethodName() + ":" + f.getLineNumber())
                .orElse("unknown"));
        blockingSites.computeIfAbsent(site, k -> new LongAdder()).increment();
    }

    /** @return 累计阻塞调用站点 top5（熔断异常的诊断后缀） */
    private String topBlockingSites() {
        return blockingSites.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .map(e -> e.getKey() + " x" + e.getValue().sum())
                .collect(Collectors.joining(", "));
    }

    /**
     * 主线程用：阻塞至上一帧执行完。若上一帧在渲染线程抛过异常，在此以
     * {@link IllegalStateException} 重抛（cause 为原始异常）——即
     * 「渲染线程异常在下一次 swapFramesAndSync 时向主线程传播」的落点。
     */
    private static void awaitCompletion(CompletableFuture<Void> completion) {
        try {
            completion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] 等待渲染帧完成时被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("[SSOptimizer] 渲染线程执行上一帧命令失败", e.getCause());
        }
    }

    @Override
    public <T> T get(Callable<T> getter) {
        return getInternal(getter, true);
    }

    @Override
    public <T> T getUncounted(Callable<T> getter) {
        return getInternal(getter, false);
    }

    private <T> T getInternal(Callable<T> getter, boolean countStall) {
        if (isRenderThread()) {
            // 渲染线程上的同步调用直接执行：再走提交队列必然自死锁
            try {
                return getter.call();
            } catch (Exception e) {
                throw new IllegalStateException("[SSOptimizer] 渲染线程内同步执行任务失败", e);
            }
        }
        // 加载期（ResourceLoaderState.init 完成前）的阻塞调用豁免熔断：加载推进
        // 画面本身就在渲染帧，纹理/字体/shader 的成批一次性分配集中在该阶段属
        // 正常形态；熔断只针对加载结束后的稳态逐帧 getter 回读。
        // 资源申请类调用（getUncounted）任何时期都不计数（见 RenderQueue 接口注释）
        if (countStall && RenderThreadMode.isLoadingFinished()) {
            recordBlockingSite();
            try {
                stallDetector.onStall();
            } catch (IllegalStateException trip) {
                throw new IllegalStateException(
                        trip.getMessage() + " 累计阻塞调用站点 top5: " + topBlockingSites(), trip);
            }
        }
        SyncTask<T> task = new SyncTask<>(getter);
        offerOrThrow(task);
        try {
            return task.result.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] 阻塞式 GL 调用被中断", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("[SSOptimizer] 渲染线程执行阻塞式调用失败", e.getCause());
        }
    }

    @Override
    public void wait(Runnable task) {
        get(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public void waitUncounted(Runnable task) {
        getUncounted(() -> {
            task.run();
            return null;
        });
    }

    @Override
    public boolean isRenderThread() {
        return Thread.currentThread() == renderThread;
    }

    /** @return 当前线程是否为主录制线程（构造本队列的线程）。 */
    public static boolean isMainThread() {
        return Thread.currentThread() == MAIN_THREAD;
    }

    /**
     * 停止渲染线程（测试/关停钩子用；游戏进程内渲染线程随 JVM 存活）。
     */
    public void shutdown() {
        running = false;
        renderThread.interrupt();
    }

    /** frameLock 内：提交当前帧到渲染线程并换发新帧。 */
    private void submitCurrentFrameLocked() {
        RenderFrame submitted = currentFrame;
        // 必须先捕获完成 Future 再 offer：offer 之后渲染线程随时可能执行完并把帧
        // 归还池（reset 换发新 Future），届时再读帧上的 Future 已不是本周期的实例
        CompletableFuture<Void> completion = submitted.completionFuture();
        offerOrThrow(new FrameTask(submitted));
        currentFrame = framePool.acquire();
        lastSubmittedCompletion = completion;
    }

    /**
     * 提交任务到渲染队列。MPSC 有界队列的 offer 在队列满时返回 false——正常深度
     * 为个位数到几十（见 {@link #submissionQueue} 注释），打满只可能是渲染线程
     * 已停止消费，属不变量破坏：记日志并抛异常，绝不静默丢任务（丢帧任务会让
     * swapFramesAndSync 永久等待，丢同步任务会让调用线程永久阻塞）。
     */
    private void offerOrThrow(RenderTask task) {
        if (!submissionQueue.offer(task)) {
            IllegalStateException failure = new IllegalStateException(String.format(
                    "[SSOptimizer] 渲染提交队列已满（容量 %d），渲染线程疑似停止消费", SUBMISSION_QUEUE_CAPACITY));
            LOGGER.error(failure.getMessage(), failure);
            throw failure;
        }
    }

    private void renderLoop() {
        while (running) {
            RenderTask task;
            try {
                task = submissionQueue.take();
            } catch (InterruptedException e) {
                if (!running) {
                    return;
                }
                LOGGER.warn("[SSOptimizer] 渲染线程被中断，继续运行", e);
                continue;
            }
            task.run();
        }
    }

    /** 渲染线程任务：帧执行或同步调用。 */
    private interface RenderTask {
        void run();
    }

    /** 一帧命令的执行任务。 */
    private final class FrameTask implements RenderTask {
        private final RenderFrame frame;

        FrameTask(RenderFrame frame) {
            this.frame = frame;
        }

        @Override
        public void run() {
            try {
                runOrRequeue(frame.commands());
                // 帧模式探针在 complete 之前：等待帧 Future 的一方由此确定探针已落地
                probeFrameErrors();
                // 帧悬挂（fence 未 signal）不视为失败：余下命令已由续跑任务接管，
                // 本帧正常完成释放主线程——主线程推进后 fence 信号才会到来
                frame.complete();
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行帧命令失败，本帧剩余命令已丢弃", t);
                frame.signalAllFences();
                // 失败帧的 GL 状态诊断价值最高，同样探一轮
                probeFrameErrors();
                frame.completeExceptionally(t);
            } finally {
                framePool.release(frame);
            }
        }
    }

    /**
     * 帧悬挂的续跑任务：携带上次悬挂点起的剩余命令，遇 fence 仍未 signal 则
     * 再次打包 requeue，直至全部执行完。不关联任何帧 Future——原帧在首次悬挂时
     * 已正常完成，续跑期间命令抛异常只记日志并丢弃余下命令（GL 状态已不可信，
     * 与帧执行失败同语义，但主线程早已放行，无 Future 可传播）。
     */
    private final class ContinuationTask implements RenderTask {
        private final List<GlCommand> commands;

        ContinuationTask(List<GlCommand> commands) {
            this.commands = commands;
        }

        @Override
        public void run() {
            try {
                runOrRequeue(commands);
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行悬挂帧的续跑命令失败，余下命令已丢弃", t);
            }
        }
    }

    /**
     * 顺序执行命令列表；遇 {@link SuspendFrameException}（fence 未 signal）时
     * 退避 {@link #SUSPEND_REQUEUE_BACKOFF_NANOS} 后把悬挂点起的剩余命令打包成
     * {@link ContinuationTask} requeue 到提交队列队尾并返回。续跑任务排在队尾，
     * 不阻塞后续帧任务与同步任务的执行。
     * <p>
     * 连续串合并：相邻且同实现的 {@link MergedBatchCommand}（顶点批次）构成
     * 一个串，整串以 {@link MergedBatchCommand#executeMerged} 协议执行——串内
     * 共享合并器（跨批次状态去重/DRAW 合并），真实 GL 调用延迟到串尾。
     */
    private void runOrRequeue(List<GlCommand> commands) {
        boolean inMergedRun = false;
        for (int i = 0; i < commands.size(); i++) {
            GlCommand command = commands.get(i);
            try {
                if (command instanceof MergedBatchCommand merged) {
                    boolean runTail = i + 1 >= commands.size()
                            || commands.get(i + 1).getClass() != command.getClass();
                    merged.executeMerged(!inMergedRun, runTail);
                    inMergedRun = !runTail;
                } else {
                    inMergedRun = false;
                    command.execute();
                }
                if (glErrorProbe == GlErrorProbe.COMMAND) {
                    boolean hadError = drainGlErrors("命令 " + probeCommandName(command) + " 之后");
                    if (hadError && command instanceof ProbeSiteCommand probe) {
                        logProbeRecordingSite(probe);
                    }
                }
            } catch (SuspendFrameException suspend) {
                LockSupport.parkNanos(SUSPEND_REQUEUE_BACKOFF_NANOS);
                offerOrThrow(new ContinuationTask(
                        new ArrayList<>(commands.subList(i, commands.size()))));
                return;
            }
        }
    }

    /** get/wait 的阻塞式同步任务。 */
    private static final class SyncTask<T> implements RenderTask {
        private final Callable<T> callable;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        SyncTask(Callable<T> callable) {
            this.callable = callable;
        }

        @Override
        public void run() {
            try {
                result.complete(callable.call());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }
    }

    /** 帧模式探针落点：帧命令执行完（无论成败）后、帧 Future 完成前排空一次。 */
    private void probeFrameErrors() {
        if (glErrorProbe == GlErrorProbe.FRAME) {
            probeFrameSequence++;
            drainGlErrors("帧 #" + probeFrameSequence + " 末尾");
        }
    }

    /**
     * 排空滞留 GL 错误并按错误码聚合计数，有错误时记 WARN。
     * 用途：诊断「真实上下文中滞留的 GL 错误被模组的 makeCurrent+glGetError
     * 健康校验（如 BoxUtil aux 线程 glInit）读到」类问题——滞留错误意味着
     * 渲染管线某处产生了失败的 GL 调用且无人察觉。
     *
     * @param site 探针位置描述（帧尾/某命令之后）
     * @return 是否排空到任何错误
     */
    private boolean drainGlErrors(final String site) {        final java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        int guard = 0;
        int error;
        while ((error = glErrorSource.getAsInt()) != 0 && guard++ < 64) {
            counts.merge(error, 1, Integer::sum);
        }
        if (counts.isEmpty()) {
            return false;
        }
        final StringBuilder sb = new StringBuilder();
        counts.forEach((code, count) ->
                sb.append("0x").append(Integer.toHexString(code)).append('x').append(count).append(' '));
        LOGGER.warn("[SSOptimizer] GL 错误探针：" + site + " 排空到滞留 GL 错误: " + sb.toString().trim());
        return true;
    }

    /** 探针日志的命令名：录制点包装命令展示被包装命令的类型名。 */
    private static String probeCommandName(final GlCommand command) {
        if (command instanceof ProbeSiteCommand probe) {
            return probe.delegate().getClass().getSimpleName();
        }
        return command.getClass().getSimpleName();
    }

    /**
     * 输出录制点包装命令的诊断堆栈（按前 6 帧指纹去重，同一录制点只打一次，
     * 总量封顶 32 个站点——标题界面每帧重复的错误不会刷屏）。
     */
    private void logProbeRecordingSite(final ProbeSiteCommand command) {
        final StackTraceElement[] trace = command.recordingSite().getStackTrace();
        final StringBuilder key = new StringBuilder();
        final int depth = Math.min(trace.length, 6);
        for (int i = 0; i < depth; i++) {
            key.append(trace[i].getClassName()).append('#').append(trace[i].getMethodName())
                    .append(':').append(trace[i].getLineNumber()).append(';');
        }
        if (probeSiteKeys.add(key.toString()) && probeSiteKeys.size() <= 32) {
            LOGGER.warn("[SSOptimizer] GL 错误探针：出错命令的录制点堆栈（诊断堆栈，非异常）",
                    command.recordingSite());
        }
    }

    /** GL 错误探针模式。 */
    enum GlErrorProbe {
        /** 关闭（默认，零开销）。 */
        OFF,
        /** 每帧命令执行完毕后排空一次。 */
        FRAME,
        /** 每条命令执行后排空一次（重，仅定位用）。 */
        COMMAND;

        static GlErrorProbe parse(final String value) {
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "frame" -> FRAME;
                case "command" -> COMMAND;
                case "off" -> OFF;
                default -> throw new IllegalArgumentException(
                        "[SSOptimizer] 非法 " + GL_ERROR_PROBE_PROPERTY + " 取值: " + value + "（允许 off/frame/command）");
            };
        }
    }
}
