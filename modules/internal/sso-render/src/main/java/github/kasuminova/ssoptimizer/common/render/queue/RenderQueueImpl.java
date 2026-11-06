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

/** 单一平台渲染线程持有 GL 上下文；frameLock 保护当前帧指针与提交。 */
public final class RenderQueueImpl implements RenderQueue {
    public static final String RENDER_THREAD_NAME = "SSOptimizer-Render";

    private static final long SUSPEND_REQUEUE_BACKOFF_NANOS = 1_000_000L;

    private static final int SUBMISSION_QUEUE_CAPACITY = 65536;

    private static final StackWalker SITE_WALKER = StackWalker.getInstance();

    public static final String GL_ERROR_PROBE_PROPERTY = "ssoptimizer.renderthread.glErrorProbe";

    public static final String STALL_POLICY_PROPERTY = "ssoptimizer.renderthread.stallPolicy";

    private static final long STALL_WARN_COOLDOWN_NANOS = 10_000_000_000L;

    private static final Logger LOGGER = Logger.getLogger(RenderQueueImpl.class);

    /** 帧尾标记是有序映射写入通道发布 fence 的序列点。 */
    public interface FrameEndMarkerHook {
        GlCommand markerFor(long frameSequence);
    }

    private static volatile FrameEndMarkerHook frameEndMarkerHook;

    public static void frameEndMarkerHook(final FrameEndMarkerHook hook) {
        frameEndMarkerHook = hook;
    }

    /** 首个非渲染线程的 swap 调用者认领主录制线程，aux 不得命中其录制缓存。 */
    private static volatile Thread mainThread = Thread.currentThread();

    /** aux 提交递增该纪元，使主线程仿真 GL 状态失效并重新同步。 */
    private static final java.util.concurrent.atomic.AtomicLong AUX_SUBMISSION_EPOCH =
            new java.util.concurrent.atomic.AtomicLong();

    private final FramePool framePool;
    private final StallDetector stallDetector;
    private final boolean stallTripCrash;
    private volatile long lastStallWarnNanos;
    private final MpscBlockingConsumerArrayQueue<RenderTask> submissionQueue =
            new MpscBlockingConsumerArrayQueue<>(SUBMISSION_QUEUE_CAPACITY);
    private final ConcurrentHashMap<String, LongAdder> blockingSites = new ConcurrentHashMap<>();
    private final Object frameLock = new Object();
    private final Thread renderThread;

    private RenderFrame currentFrame;
    private long frameSequenceCounter;
    private volatile long lastSubmittedSequence = -1;
    /** 必须在 offer 前捕获；帧归还池后 reset 会换发 Future。 */
    private CompletableFuture<Void> lastSubmittedCompletion;

    private volatile boolean running = true;

    private final GlErrorProbe glErrorProbe;
    private final IntSupplier glErrorSource;
    private long probeFrameSequence;
    private final java.util.Set<String> probeSiteKeys = new java.util.HashSet<>();

    private volatile Runnable frameFailureHook;

    public void setFrameFailureHook(final Runnable hook) {
        this.frameFailureHook = hook;
    }

    public RenderQueueImpl() {
        this(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector());
    }

    public RenderQueueImpl(FramePool framePool, StallDetector stallDetector) {
        this(framePool, stallDetector,
                GlErrorProbe.parse(System.getProperty(GL_ERROR_PROBE_PROPERTY, "off")),
                // Display 未创建时 glGetError 没有上下文，视为无错误。
                () -> org.lwjgl.opengl.Display.isCreated() ? org.lwjgl.opengl.GL11.glGetError() : 0);
    }

    RenderQueueImpl(FramePool framePool, StallDetector stallDetector,
                    GlErrorProbe glErrorProbe, IntSupplier glErrorSource) {
        this.framePool = framePool;
        this.stallDetector = stallDetector;
        this.stallTripCrash = parseStallTripCrash(System.getProperty(STALL_POLICY_PROPERTY, "warn"));
        this.glErrorProbe = glErrorProbe;
        this.glErrorSource = glErrorSource;
        this.currentFrame = framePool.acquire();
        this.currentFrame.assignSequence(this.frameSequenceCounter++);
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
        Thread producer = Thread.currentThread();
        if (producer != mainThread && producer != renderThread) {
            AUX_SUBMISSION_EPOCH.incrementAndGet();
        }
        synchronized (frameLock) {
            currentFrame.addUnlocked(command);
        }
    }

    @Override
    public void swapFrames() {
        synchronized (frameLock) {
            submitCurrentFrameLocked();
        }
        // drain-first 通道不推进 stall 窗口，帧边界只由 swapFramesAndSync 标记。
    }

    @Override
    public void swapFramesAndSync() {
        claimMainThreadIfNeeded();
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

    private String topBlockingSites() {
        return blockingSites.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(5)
                .map(e -> e.getKey() + " x" + e.getValue().sum())
                .collect(Collectors.joining(", "));
    }

    private void onStallTrip() {
        String diagnostics = stallDetector.tripDiagnostics()
                + " 累计阻塞调用站点 top5: " + topBlockingSites();
        if (stallTripCrash) {
            throw new IllegalStateException("[SSOptimizer] " + diagnostics);
        }
        long now = System.nanoTime();
        if (now - lastStallWarnNanos >= STALL_WARN_COOLDOWN_NANOS) {
            lastStallWarnNanos = now;
            LOGGER.warn("[SSOptimizer] " + diagnostics
                    + "（warn 策略：调用仍被正确服务，仅失去管线并行收益；开发排障可设 -D"
                    + STALL_POLICY_PROPERTY + "=crash 直接抛出）");
        }
    }

    static boolean parseStallTripCrash(String value) {
        if ("crash".equalsIgnoreCase(value)) {
            return true;
        }
        if (!"warn".equalsIgnoreCase(value)) {
            LOGGER.warn("[SSOptimizer] 无法识别的 stall 熔断策略 '" + value
                    + "'，按 warn 处理（可选 warn/crash，属性 " + STALL_POLICY_PROPERTY + "）");
        }
        return false;
    }

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
            // 渲染线程上的同步调用必须直接执行，否则提交队列会自死锁。
            try {
                return getter.call();
            } catch (Exception e) {
                throw new IllegalStateException("[SSOptimizer] 渲染线程内同步执行任务失败", e);
            }
        }
        if (countStall && RenderThreadMode.isLoadingFinished()) {
            recordBlockingSite();
            if (stallDetector.onStall()) {
                onStallTrip();
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

    public static boolean isMainThread() {
        return Thread.currentThread() == mainThread;
    }

    public static long auxSubmissionEpoch() {
        return AUX_SUBMISSION_EPOCH.get();
    }

    public static void resetAuxSubmissionEpochForTesting() {
        AUX_SUBMISSION_EPOCH.set(0);
    }

    private void claimMainThreadIfNeeded() {
        final Thread current = Thread.currentThread();
        if (current == renderThread) {
            return;
        }
        final Thread existing = mainThread;
        if (existing != current) {
            mainThread = current;
            LOGGER.info("[SSOptimizer] 主录制线程认领：\"" + existing.getName()
                    + "\" -> \"" + current.getName() + "\"");
        }
    }

    public void shutdown() {
        running = false;
        renderThread.interrupt();
    }

    private void submitCurrentFrameLocked() {
        RenderFrame submitted = currentFrame;
        // 必须在 offer 前捕获 Future，帧归还池后 reset 会换发实例。
        CompletableFuture<Void> completion = submitted.completionFuture();
        final FrameEndMarkerHook markerHook = frameEndMarkerHook;
        if (markerHook != null) {
            final GlCommand marker = markerHook.markerFor(submitted.sequence());
            if (marker != null) {
                submitted.addUnlocked(marker);
            }
        }
        lastSubmittedSequence = submitted.sequence();
        RtTrace.frameBoundary();
        offerOrThrow(new FrameTask(submitted));
        currentFrame = framePool.acquire();
        currentFrame.assignSequence(frameSequenceCounter++);
        lastSubmittedCompletion = completion;
    }

    public long lastSubmittedSequence() {
        return lastSubmittedSequence;
    }

    /** offer 失败必须抛异常，静默丢任务会造成永久等待。 */
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

    private interface RenderTask {
        void run();
    }

    private final class FrameTask implements RenderTask {
        private final RenderFrame frame;

        FrameTask(RenderFrame frame) {
            this.frame = frame;
        }

        @Override
        public void run() {
            try {
                RtTrace.trace("FRAME_EXEC", frame.commands().size(), 0, 0, null);
                runOrRequeue(frame.commands());
                probeFrameErrors();
                frame.complete();
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行帧命令失败，本帧剩余命令已丢弃", t);
                frame.signalAllFences();
                probeFrameErrors();
                runFrameFailureHook();
                frame.completeExceptionally(t);
            } finally {
                framePool.release(frame);
            }
        }
    }

    private final class ContinuationTask implements RenderTask {
        private final List<GlCommand> commands;

        ContinuationTask(List<GlCommand> commands) {
            this.commands = commands;
        }

        @Override
        public void run() {
            try {
                RtTrace.trace("CONT_EXEC", commands.size(), 0, 0, null);
                runOrRequeue(commands);
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行悬挂帧的续跑命令失败，余下命令已丢弃", t);
                runFrameFailureHook();
            }
        }
    }

    private void runFrameFailureHook() {
        final Runnable hook = frameFailureHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (Throwable hookError) {
            LOGGER.warn("[SSOptimizer] 帧失败钩子执行异常（已忽略，不影响渲染线程主循环）", hookError);
        }
    }

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
                RtTrace.trace("SUSPEND", i, commands.size(), 0, null);
                LockSupport.parkNanos(SUSPEND_REQUEUE_BACKOFF_NANOS);
                offerOrThrow(new ContinuationTask(
                        new ArrayList<>(commands.subList(i, commands.size()))));
                return;
            }
        }
    }

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

    private void probeFrameErrors() {
        if (glErrorProbe == GlErrorProbe.FRAME) {
            probeFrameSequence++;
            drainGlErrors("帧 #" + probeFrameSequence + " 末尾");
        }
    }

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

    private static String probeCommandName(final GlCommand command) {
        if (command instanceof ProbeSiteCommand probe) {
            return probe.delegate().getClass().getSimpleName();
        }
        return command.getClass().getSimpleName();
    }

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

    enum GlErrorProbe {
        OFF,
        FRAME,
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
