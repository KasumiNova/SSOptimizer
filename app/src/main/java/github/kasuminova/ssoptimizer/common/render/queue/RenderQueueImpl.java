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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
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

    private static final Logger LOGGER = Logger.getLogger(RenderQueueImpl.class);

    /**
     * aux 命令游程的状态围栏（见 {@link AuxRunFence}）：默认 {@link AuxRunFence#NOOP}，
     * 生产装配（SSOptimizerCorePlugin）在安装队列时注入真实实现。渲染线程
     * 执行循环据此隔离 aux 线程命令对游戏侧 GL 状态的污染。
     */
    private static volatile AuxRunFence auxRunFence = AuxRunFence.NOOP;

    /**
     * 渲染线程 display list 编译窗口标记（仅渲染线程读写）：bridge 的
     * glNewList/glEndList 命令体经 {@link #onListCompileBegin()}/
     * {@link #onListCompileEnd()} 翻转。静态而非实例字段：帧悬挂续跑与后续帧
     * 共用同一 GL 上下文的编译状态。
     */
    private static boolean listRecording;
    /**
     * 编译窗口内到达的 aux 命令延迟队列（仅渲染线程读写）：窗口关闭或帧/续跑
     * 末尾由 {@link #drainDeferredAuxCommands()} 补执行，悬挂 requeue 时留存
     * 给续跑任务接续（命令不丢、相对序不变）。
     */
    private static final List<GlCommand> deferredAuxCommands = new ArrayList<>();

    /** 当前执行回合是否处于 aux 游程内（仅渲染线程读写；回合结束在 finally 中复位）。 */
    private boolean inAuxRun;

    /** 诊断计数（跨实例累计）：aux 生产者提交的命令总数（录制侧活性证据，bridge 侧递增）。 */
    public static final AtomicLong auxCommandsSubmitted = new AtomicLong();
    /** 诊断计数：aux 游程进入次数（围栏触发证据）。 */
    private static final AtomicLong auxRunCount = new AtomicLong();
    /** 诊断计数：围栏内执行的 aux 命令总数。 */
    private static final AtomicLong auxCommandCount = new AtomicLong();
    /** 诊断计数：display list 编译窗口内被延迟的 aux 命令总数。 */
    private static final AtomicLong auxDeferredTotal = new AtomicLong();

    /**
     * 主录制线程：生产环境为类初始化线程（主线程装配队列时触发，即游戏主线程）。
     * bridge 的帧录制上下文帧边界缓存（{@link BridgeSupport#recordingContext()}）
     * 与 aux 生产者判定（{@code BridgeSupport#isAuxProducer}）依赖此判定——
     * aux-context 生产者线程不得命中主线程缓存、其命令必须包装来源标记。
     * 测试 JVM 内类初始化时机取决于用例执行序，须用
     * {@link #captureMainThreadForTesting()} 在断言主/aux 行为前显式锚定。
     */
    private static volatile Thread mainThread = Thread.currentThread();

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
        this.framePool = framePool;
        this.stallDetector = stallDetector;
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
            currentFrame.add(command);
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

    /** @return 当前线程是否为主录制线程。 */
    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    /**
     * 渲染线程执行侧的 display list 编译窗口开始（bridge 的 glNewList 命令体在
     * 真实 glNewList 成功后调用）。窗口内到达的 aux 命令（{@link AuxOriginCommand}）
     * 必须延迟到窗口关闭后执行——否则 aux 命令会被驱动编译进列表本体，每次
     * glCallList 永久重放（glyph/字符串显示列表腐坏的根因，症状为随机 onset
     * 且持续整局）。
     */
    public static void onListCompileBegin() {
        listRecording = true;
    }

    /** {@link #onListCompileBegin()} 的配对结束（bridge 的 glEndList 命令体调用）。 */
    public static void onListCompileEnd() {
        listRecording = false;
    }

    /** 测试用：复位编译窗口标记与延迟队列，避免用例间静态状态串扰。 */
    static void resetListCompileStateForTesting() {
        listRecording = false;
        deferredAuxCommands.clear();
    }

    /**
     * 测试用：把主录制线程判定锚定到调用线程。静态初始化捕获的线程在测试 JVM
     * 内不确定（取决于哪个用例先触发类初始化），断言主/aux 录制行为的用例
     * 必须在 setUp 调用本方法消除执行序依赖。
     */
    public static void captureMainThreadForTesting() {
        mainThread = Thread.currentThread();
    }

    /**
     * 注入 aux 游程状态围栏（生产装配在安装队列时调用一次；测试注入计数假实现
     * 验证游程状态机的 enter/exit 配对，用毕应重置为 {@link AuxRunFence#NOOP}
     * 避免跨用例静态状态串扰）。
     */
    public static void installAuxRunFence(AuxRunFence fence) {
        auxRunFence = fence;
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
        // 分段扁平化必须在提交前完成（并行段不变量：编排器屏障先于 swap，
        // 此处封存全部段）；单段帧零拷贝，非并行帧无额外开销
        submitted.flatten();
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
                // 帧悬挂（fence 未 signal）不视为失败：余下命令已由续跑任务接管，
                // 本帧正常完成释放主线程——主线程推进后 fence 信号才会到来
                frame.complete();
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行帧命令失败，本帧剩余命令已丢弃", t);
                frame.signalAllFences();
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
     * aux 游程状态机：连续的 {@link AuxOriginCommand} 构成一个游程，进入首个
     * aux 命令前 {@link AuxRunFence#enter()} 快照游戏侧 GL 状态，遇非 aux 命令
     * 或列表结束（含悬挂 requeue、异常中断，finally 兜底）时 exit 恢复——aux
     * 线程命令的状态改写被隔离在游程内部。悬挂边界会先 exit 再 requeue，续跑
     * 遇后续 aux 命令重新 enter，保证游程不跨越其他任务（getter 读到的一定是
     * 恢复后的游戏状态）。
     * <p>
     * display list 编译窗口：{@link #listRecording} 期间到达的 aux 命令不执行，
     * 暂存 {@link #deferredAuxCommands}（驱动会把窗口内执行的命令编译进列表
     * 本体，aux 命令混入即列表永久腐坏）；窗口关闭后（glEndList 命令执行完）
     * 就地补执行，悬挂/异常中断时留存给续跑或后续 drain 点。
     */
    private void runOrRequeue(List<GlCommand> commands) {
        try {
            for (int i = 0; i < commands.size(); i++) {
                GlCommand command = commands.get(i);
                if (listRecording && command instanceof AuxOriginCommand) {
                    auxDeferredTotal.incrementAndGet();
                    deferredAuxCommands.add(command);
                    continue;
                }
                try {
                    executeWithFence(command);
                } catch (SuspendFrameException suspend) {
                    LockSupport.parkNanos(SUSPEND_REQUEUE_BACKOFF_NANOS);
                    offerOrThrow(new ContinuationTask(
                            new ArrayList<>(commands.subList(i, commands.size()))));
                    return;
                }
                if (!listRecording && !deferredAuxCommands.isEmpty()) {
                    drainDeferredAuxCommands();
                }
            }
        } finally {
            if (inAuxRun) {
                auxRunFence.exit();
                inAuxRun = false;
            }
        }
    }

    /**
     * 经 aux 游程围栏执行单条命令：aux 命令进入/离开游程时翻转围栏
     * （{@link AuxRunFence#enter()}/{@link #exit()}），同游程内连续 aux 命令
     * 共享一次快照/恢复。
     */
    private void executeWithFence(GlCommand command) {
        boolean aux = command instanceof AuxOriginCommand;
        if (aux != inAuxRun) {
            if (aux) {
                auxRunFence.enter();
                long runs = auxRunCount.incrementAndGet();
                if (runs % 600 == 0) {
                    // 低频活性遥测：腐坏排查用（aux 无活性则污染理论不成立）
                    LOGGER.info("[SSOptimizer] aux 围栏统计: 游程=" + runs
                            + " 围栏内aux命令=" + auxCommandCount.get()
                            + " 编译窗口延迟=" + auxDeferredTotal.get()
                            + " 录制侧aux提交=" + auxCommandsSubmitted.get());
                }
            } else {
                auxRunFence.exit();
            }
            inAuxRun = aux;
        }
        if (aux) {
            auxCommandCount.incrementAndGet();
        }
        command.execute();
    }

    /**
     * 补执行编译窗口内延迟的 aux 命令（经 {@link #executeWithFence} 正常进围栏）。
     * 逐条移除：中途抛 {@link SuspendFrameException}（aux 命令携带的 fence 等待）
     * 时余下命令留存，由续跑任务的 drain 点接续，不丢命令。
     */
    private void drainDeferredAuxCommands() {
        int executed = 0;
        try {
            for (; executed < deferredAuxCommands.size(); executed++) {
                executeWithFence(deferredAuxCommands.get(executed));
            }
        } finally {
            if (executed > 0) {
                deferredAuxCommands.subList(0, executed).clear();
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
}
