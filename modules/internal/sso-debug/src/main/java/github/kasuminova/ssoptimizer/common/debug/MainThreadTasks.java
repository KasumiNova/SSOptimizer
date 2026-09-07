package github.kasuminova.ssoptimizer.common.debug;

import org.apache.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

/**
 * 主线程任务队列：脚本 {@code main} 执行模式的投递通道。
 *
 * <p>动机：读写游戏业务状态的脚本必须与游戏主循环互斥。任务经 {@link #call}
 * 入队并阻塞等待（上限 {@code CALL_TIMEOUT_MS}，超时快速失败）；每帧迭代由
 * {@code MainThreadTasksDrainMixin}（注入 {@code BaseGameState.traverse} 循环体内的
 * {@code SoundManager.advance} 调用点）在游戏主线程回调 {@link #drain()} 排空执行。
 * 任务异常不回传游戏主循环（逐任务捕获并 completeExceptionally +
 * 日志），单个脚本失败不打断帧循环。</p>
 */
public final class MainThreadTasks {
    private static final Logger LOGGER = Logger.getLogger(MainThreadTasks.class);

    private static final Queue<Task<?>> QUEUE = new ConcurrentLinkedQueue<>();
    /** drain 调用计数（诊断/RPC 遥测：主循环存活性探针）。 */
    private static final java.util.concurrent.atomic.AtomicLong DRAIN_COUNT
            = new java.util.concurrent.atomic.AtomicLong();

    /** main 模式任务等待上限：超时说明主循环未运行（加载期/空转暂停），快速失败而非挂起请求。 */
    private static final long CALL_TIMEOUT_MS = 10_000;

    private MainThreadTasks() {
    }

    /**
     * 返回 drain 累计调用次数（诊断主循环是否在跑）。
     *
     * @return drain 调用总数
     */
    public static long drainCount() {
        return DRAIN_COUNT.get();
    }

    /**
     * 投递任务到主线程并阻塞等待结果。
     *
     * @param callable 任务体
     * @param <T>      结果类型
     * @return 任务结果
     * @throws Exception 任务抛出的异常原样重抛（Error 以 RuntimeException 包装）
     */
    public static <T> T call(final Callable<T> callable) throws Exception {
        final Task<T> task = new Task<>(callable, new CompletableFuture<>());
        QUEUE.add(task);
        try {
            return task.future().get(CALL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] MainThreadTasks call interrupted", e);
        } catch (final java.util.concurrent.TimeoutException e) {
            QUEUE.remove(task);
            throw new IllegalStateException("[SSOptimizer] MainThreadTasks: main loop did not drain within "
                    + CALL_TIMEOUT_MS + "ms (game loop not running or window-idle paused?)", e);
        } catch (final ExecutionException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException("[SSOptimizer] MainThreadTasks task failed with error", cause);
        }
    }

    /**
     * 排空队列并在调用线程（游戏主线程）逐任务执行。每帧迭代由
     * {@code MainThreadTasksDrainMixin} 注入调用。
     */
    public static void drain() {
        DRAIN_COUNT.incrementAndGet();
        Task<?> task;
        while ((task = QUEUE.poll()) != null) {
            runTask(task);
        }
    }

    private static <T> void runTask(final Task<T> task) {
        try {
            task.future().complete(task.callable().call());
        } catch (final Throwable t) {
            LOGGER.error("[SSOptimizer] Main-thread debug task failed", t);
            task.future().completeExceptionally(t);
        }
    }

    private record Task<T>(Callable<T> callable,
                           CompletableFuture<T> future) {
    }
}
