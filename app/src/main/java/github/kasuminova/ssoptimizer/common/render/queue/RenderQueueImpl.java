package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;

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
 */
public final class RenderQueueImpl implements RenderQueue {
    /** 渲染线程名，profiler / 日志诊断用。 */
    public static final String RENDER_THREAD_NAME = "SSOptimizer-Render";

    private static final Logger LOGGER = Logger.getLogger(RenderQueueImpl.class);

    private final FramePool framePool;
    private final StallDetector stallDetector;
    private final LinkedBlockingQueue<RenderTask> submissionQueue = new LinkedBlockingQueue<>();
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
     * @param stallDetector 阻塞式调用熔断器（每次 get/wait 阻塞调用计数）
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
        stallDetector.onSwap();
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
        if (isRenderThread()) {
            // 渲染线程上的同步调用直接执行：再走提交队列必然自死锁
            try {
                return getter.call();
            } catch (Exception e) {
                throw new IllegalStateException("[SSOptimizer] 渲染线程内同步执行任务失败", e);
            }
        }
        stallDetector.onStall();
        SyncTask<T> task = new SyncTask<>(getter);
        submissionQueue.offer(task);
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
    public boolean isRenderThread() {
        return Thread.currentThread() == renderThread;
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
        submissionQueue.offer(new FrameTask(submitted));
        currentFrame = framePool.acquire();
        lastSubmittedCompletion = completion;
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
                for (GlCommand command : frame.commands()) {
                    command.execute();
                }
                frame.complete();
            } catch (Throwable t) {
                LOGGER.error("[SSOptimizer] 渲染线程执行帧命令失败，本帧剩余命令已丢弃", t);
                frame.completeExceptionally(t);
            } finally {
                framePool.release(frame);
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
