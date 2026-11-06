package github.kasuminova.ssoptimizer.common.concurrent;

import org.apache.log4j.Logger;

/**
 * 全局共享的帧内 CPU 任务工作池，主线程必须在同帧调用屏障等待任务完成。
 * 禁止提交阻塞或长耗时任务；IO 与一次性批任务使用 {@link VtWorkers}。
 * 相同 stripeKey 的任务按身份哈希分配到同一工作线程串行执行。
 */
public final class SharedFrameWorkers {
    public static final String THREADS_PROPERTY = "ssoptimizer.workers.threads";

    private static final Logger LOGGER = Logger.getLogger(SharedFrameWorkers.class);

    private static volatile FrameParallelExecutorImpl instance;

    private SharedFrameWorkers() {
    }

    public static FrameParallelExecutor get() {
        FrameParallelExecutorImpl local = instance;
        if (local == null) {
            synchronized (SharedFrameWorkers.class) {
                local = instance;
                if (local == null) {
                    final int threads = resolveThreadCount(System.getProperty(THREADS_PROPERTY));
                    LOGGER.info("[SSOptimizer] Shared frame workers created with " + threads + " thread(s)");
                    local = new FrameParallelExecutorImpl("Shared", threads);
                    instance = local;
                }
            }
        }
        return local;
    }

    public static boolean isWorkerThread() {
        final FrameParallelExecutorImpl local = instance;
        return local != null && local.isWorkerThread();
    }

    static int resolveThreadCount(final String raw) {
        final int defaultCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        if (raw == null) {
            return defaultCount;
        }

        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            LOGGER.warn("[SSOptimizer] " + THREADS_PROPERTY + " 取值 \"" + raw
                    + "\" 无法解析，按默认 " + defaultCount + " 处理", e);
            return defaultCount;
        }

        if (parsed < 1) {
            LOGGER.warn("[SSOptimizer] " + THREADS_PROPERTY + " 取值 " + parsed
                    + " 非法（必须 ≥1），按默认 " + defaultCount + " 处理");
            return defaultCount;
        }
        return parsed;
    }
}
