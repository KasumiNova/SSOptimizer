package github.kasuminova.ssoptimizer.common.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 虚拟线程任务门面，适用于 IO 阻塞与一次性批任务。
 * 不提供帧内屏障；调用方必须通过 {@link Future} 或 {@link #awaitAll(Future[])} 等待结果与异常。
 */
public final class VtWorkers {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private VtWorkers() {
    }

    public static Future<?> submit(final Runnable task) {
        return EXECUTOR.submit(task);
    }

    public static <T> Future<T> submit(final Callable<T> task) {
        return EXECUTOR.submit(task);
    }

    /** 等待全部任务结束后返回；失败时以 {@link RuntimeException} 重抛首个异常，其余异常挂为 suppressed。 */
    public static void awaitAll(final Future<?>... futures) {
        Throwable firstFailure = null;
        RuntimeException propagated = null;
        for (final Future<?> future : futures) {
            try {
                future.get();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("[SSOptimizer] VtWorkers awaitAll interrupted", e);
            } catch (final ExecutionException e) {
                if (firstFailure == null) {
                    firstFailure = e.getCause();
                    propagated = new RuntimeException("[SSOptimizer] VtWorkers task failed", firstFailure);
                } else {
                    propagated.addSuppressed(e.getCause());
                }
            }
        }
        if (propagated != null) {
            throw propagated;
        }
    }
}
