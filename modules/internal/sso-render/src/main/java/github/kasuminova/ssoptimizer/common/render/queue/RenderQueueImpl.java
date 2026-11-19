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
            ensureRunning();
            return currentFrame;
        }
    }

    @Override
    public void submit(GlCommand command) {
        Thread producer = Thread.currentThread();
        synchronized (frameLock) {
            ensureRunning();
            if (producer != mainThread && producer != renderThread) {
                AUX_SUBMISSION_EPOCH.incrementAndGet();
            }
            if (glErrorProbe == GlErrorProbe.COMMAND && command instanceof DiscardableGlCommand
                    && !(command instanceof MergedBatchCommand)) {
                command = new ProbeSiteCommand(command);
            }
            currentFrame.addUnlocked(command);
        }
    }

    @Override
    public void swapFrames() {
        RejectedFrame rejected;
        synchronized (frameLock) {
            rejected = submitCurrentFrameLocked();
        }
        releaseRejectedFrame(rejected);
    }

    @Override
    public void swapFramesAndSync() {
        claimMainThreadIfNeeded();
        CompletableFuture<Void> previousCompletion;
        RejectedFrame rejected;
        synchronized (frameLock) {
            previousCompletion = lastSubmittedCompletion;
            rejected = submitCurrentFrameLocked();
        }
        releaseRejectedFrame(rejected);
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
        synchronized (frameLock) {
            if (!running) {
                return;
            }
            running = false;
        }
        renderThread.interrupt();
    }

    private RejectedFrame submitCurrentFrameLocked() {
        ensureRunning();
        RenderFrame submitted = currentFrame;
        long sequence = submitted.sequence();
        CompletableFuture<Void> completion = submitted.completionFuture();
        RenderFrame next = framePool.acquire();
        final FrameEndMarkerHook markerHook = frameEndMarkerHook;
        GlCommand marker = null;
        try {
            if (markerHook != null) {
                marker = markerHook.markerFor(sequence);
                if (marker != null) {
                    submitted.addUnlocked(marker);
                    marker = null;
                }
            }
            RtTrace.frameBoundary();
            offerLocked(new FrameTask(submitted));
        } catch (RuntimeException | Error failure) {
            // 有序映射写入按相邻帧号等待 GPU fence，提交失败时必须保留该帧号。
            next.assignSequence(sequence);
            currentFrame = next;
            return new RejectedFrame(submitted, marker, failure);
        }
        lastSubmittedSequence = sequence;
        next.assignSequence(frameSequenceCounter++);
        currentFrame = next;
        lastSubmittedCompletion = completion;
        return null;
    }

    private void releaseRejectedFrame(RejectedFrame rejected) {
        if (rejected == null) {
            return;
        }
        Throwable failure = rejected.failure();
        discardCommand(rejected.unrecordedMarker(), failure);
        discardFrame(rejected.frame(), failure);
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    private record RejectedFrame(RenderFrame frame, GlCommand unrecordedMarker, Throwable failure) {
    }

    public long lastSubmittedSequence() {
        return lastSubmittedSequence;
    }

    private void ensureRunning() {
        if (!running) {
            throw new QueueClosedException();
        }
    }

    private void offerOrThrow(RenderTask task) {
        synchronized (frameLock) {
            offerLocked(task);
        }
    }

    private void offerLocked(RenderTask task) {
        ensureRunning();
        if (!submissionQueue.offer(task)) {
            IllegalStateException failure = new IllegalStateException(String.format(
                    "[SSOptimizer] 渲染提交队列已满（容量 %d）", SUBMISSION_QUEUE_CAPACITY));
            LOGGER.error(failure.getMessage(), failure);
            throw failure;
        }
    }

    private void renderLoop() {
        Throwable termination = new QueueClosedException();
        try {
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
                if (running) {
                    task.run();
                } else {
                    task.discard(termination);
                }
            }
        } catch (Throwable failure) {
            termination = failure;
            LOGGER.error("[SSOptimizer] 渲染线程异常退出", failure);
        } finally {
            discardPending(termination);
        }
    }

    private void discardPending(Throwable failure) {
        RenderFrame recorded;
        synchronized (frameLock) {
            running = false;
            recorded = currentFrame;
            currentFrame = null;
        }
        discardFrame(recorded, failure);
        RenderTask task;
        while ((task = submissionQueue.poll()) != null) {
            task.discard(failure);
        }
    }

    private static final class QueueClosedException extends IllegalStateException {
        QueueClosedException() {
            super("[SSOptimizer] 渲染队列已关闭");
        }
    }

    private interface RenderTask {
        void run();

        void discard(Throwable failure);
    }

    private final class FrameTask implements RenderTask {
        private final RenderFrame frame;

        FrameTask(RenderFrame frame) {
            this.frame = frame;
        }

        @Override
        public void run() {
            boolean continued = false;
            boolean probing = false;
            try {
                continued = runOrRequeue("FRAME_EXEC", frame.commands(), frame.fences());
                probing = true;
                probeFrameErrors();
                frame.complete();
            } catch (Throwable failure) {
                try {
                    if (!continued) {
                        signalFences(frame.fences(), failure);
                    }
                    if (!(failure instanceof QueueClosedException)) {
                        if (!probing) {
                            probeFailedFrame(failure);
                        }
                        LOGGER.error("[SSOptimizer] 渲染帧执行失败", failure);
                        runFrameFailureHook();
                    }
                } finally {
                    frame.completeExceptionally(failure);
                }
            } finally {
                framePool.release(frame);
            }
        }

        @Override
        public void discard(Throwable failure) {
            discardFrame(frame, failure);
        }
    }

    private final class ContinuationTask implements RenderTask {
        private final List<GlCommand> commands;
        private final List<FrameFence> fences;

        ContinuationTask(List<GlCommand> commands, List<FrameFence> fences) {
            this.commands = commands;
            this.fences = fences;
        }

        @Override
        public void run() {
            try {
                runOrRequeue("CONT_EXEC", commands, fences);
            } catch (Throwable failure) {
                signalFences(fences, failure);
                if (!(failure instanceof QueueClosedException)) {
                    LOGGER.error("[SSOptimizer] 渲染帧续跑失败", failure);
                    runFrameFailureHook();
                }
            }
        }

        @Override
        public void discard(Throwable failure) {
            discardCommands(commands, 0, failure);
            signalFences(fences, failure);
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

    private boolean runOrRequeue(String trace, List<GlCommand> commands, List<FrameFence> fences) {
        int next = 0;
        boolean inMergedRun = false;
        try {
            RtTrace.trace(trace, commands.size(), 0, 0, null);
            for (int i = 0; i < commands.size(); i++) {
                ensureRunning();
                GlCommand command = commands.get(i);
                try {
                    if (command instanceof MergedBatchCommand merged) {
                        boolean runTail = i + 1 >= commands.size()
                                || commands.get(i + 1).getClass() != command.getClass();
                        next = i + 1;
                        merged.executeMerged(!inMergedRun, runTail);
                        inMergedRun = !runTail;
                    } else {
                        next = i + 1;
                        command.execute();
                        inMergedRun = false;
                    }
                } catch (SuspendFrameException suspend) {
                    if (ProbeSiteCommand.unwrap(command) instanceof DiscardableGlCommand) {
                        throw new IllegalStateException("[SSOptimizer] 持有一次性资源的 GL 命令请求续跑", suspend);
                    }
                    next = i;
                    RtTrace.trace("SUSPEND", i, commands.size(), 0, null);
                    LockSupport.parkNanos(SUSPEND_REQUEUE_BACKOFF_NANOS);
                    offerOrThrow(new ContinuationTask(
                            new ArrayList<>(commands.subList(i, commands.size())), new ArrayList<>(fences)));
                    return true;
                }
                if (glErrorProbe == GlErrorProbe.COMMAND) {
                    boolean hadError = drainGlErrors("命令 " + probeCommandName(command) + " 之后");
                    if (hadError && command instanceof ProbeSiteCommand probe) {
                        logProbeRecordingSite(probe);
                    }
                }
            }
            return false;
        } catch (Throwable failure) {
            discardCommands(commands, next, failure);
            throw failure;
        }
    }

    private void discardFrame(RenderFrame frame, Throwable failure) {
        try {
            discardCommands(frame.commands(), 0, failure);
            signalFences(frame.fences(), failure);
        } finally {
            frame.completeExceptionally(failure);
            framePool.release(frame);
        }
    }

    private static void discardCommands(List<GlCommand> commands, int start, Throwable failure) {
        for (int i = start; i < commands.size(); i++) {
            discardCommand(commands.get(i), failure);
        }
    }

    private static void discardCommand(GlCommand command, Throwable failure) {
        if (ProbeSiteCommand.unwrap(command) instanceof DiscardableGlCommand owned) {
            try {
                owned.discard();
            } catch (Throwable discardFailure) {
                reportCleanupFailure("[SSOptimizer] 归还未执行的 GL 命令资源失败", failure, discardFailure);
            }
        }
    }

    private static void signalFences(List<FrameFence> fences, Throwable failure) {
        for (FrameFence fence : fences) {
            try {
                fence.signal();
            } catch (Throwable signalFailure) {
                reportCleanupFailure("[SSOptimizer] 放行失败渲染帧的 fence 时出错", failure, signalFailure);
            }
        }
    }

    private void probeFailedFrame(Throwable failure) {
        try {
            probeFrameErrors();
        } catch (Throwable probeFailure) {
            reportCleanupFailure("[SSOptimizer] 检查失败渲染帧的 GL 错误时出错", failure, probeFailure);
        }
    }

    private static void reportCleanupFailure(String message, Throwable failure, Throwable cleanupFailure) {
        if (cleanupFailure != failure) {
            failure.addSuppressed(cleanupFailure);
        }
        LOGGER.error(message, cleanupFailure);
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
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        @Override
        public void discard(Throwable failure) {
            result.completeExceptionally(failure);
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
