package github.kasuminova.ssoptimizer.common.debug;

import org.apache.log4j.Logger;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;

/** call 阻塞等待游戏主线程排空，超时失败；drain 捕获任务异常以避免打断帧循环。 */
public final class MainThreadTasks {
    private static final Logger LOGGER = Logger.getLogger(MainThreadTasks.class);

    private static final Queue<Task<?>> QUEUE = new ConcurrentLinkedQueue<>();
    private static final java.util.concurrent.atomic.AtomicLong DRAIN_COUNT
            = new java.util.concurrent.atomic.AtomicLong();

    private static final long CALL_TIMEOUT_MS = 10_000;

    private MainThreadTasks() {
    }

    public static long drainCount() {
        return DRAIN_COUNT.get();
    }

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
