package github.kasuminova.ssoptimizer.common.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * 虚拟线程任务门面。
 * <p>
 * 动机：IO 阻塞或一次性批任务（如后台解码、磁盘读写、一次性预热）不适合占用
 * {@link SharedFrameWorkers} 的帧内屏障工作线程；虚拟线程按任务即时创建、阻塞
 * 不占用载体线程，是这类负载的统一出口。
 * <p>
 * 使用约束：<b>禁止提交帧内屏障任务</b>——本门面不提供 {@code awaitAll} 帧屏障语义，
 * 帧内 CPU 密集并行任务必须使用 {@link SharedFrameWorkers}。提交的任务应通过返回的
 * {@link Future} 或 {@link #awaitAll(Future[])} 显式等待结果，不得提交后放任不管。
 * <p>
 * 实现：全局唯一的 {@code newVirtualThreadPerTaskExecutor} 单例，随用随建线程、
 * 用完即回收，无需调用方管理生命周期。
 */
public final class VtWorkers {
    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private VtWorkers() {
    }

    /**
     * 提交一个无返回值任务到虚拟线程执行。
     *
     * @param task 任务体
     * @return 任务句柄，用于等待完成或获取异常
     */
    public static Future<?> submit(final Runnable task) {
        return EXECUTOR.submit(task);
    }

    /**
     * 提交一个有返回值任务到虚拟线程执行。
     *
     * @param task 任务体
     * @param <T>  返回值类型
     * @return 任务句柄，用于等待完成并取回结果
     */
    public static <T> Future<T> submit(final Callable<T> task) {
        return EXECUTOR.submit(task);
    }

    /**
     * 等待全部任务完成。
     * <p>
     * 按传入顺序逐个 {@link Future#get()}，保证返回时全部任务已结束；任一任务失败
     * 时等待其余任务完成后，以 {@link RuntimeException} 重抛首个失败（其余异常
     * 挂为 suppressed），不吞异常。
     *
     * @param futures 待等待的任务句柄
     */
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
