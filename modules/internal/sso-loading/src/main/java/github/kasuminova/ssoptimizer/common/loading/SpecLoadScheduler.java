package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;
import org.json.JSONException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Spec 加载 DAG 并行调度器。
 * <p>
 * 把 {@code SpecStore.loadStarmap} 的扁平串行调用序列重组为带依赖声明的任务图：
 * 无依赖或依赖已完成的任务在共享线程池上并行执行，依赖未完成时自动等待。
 * 任务体异常会被包装携带任务名后提前终止整张图，并在 barrier 处解包重抛，
 * 保持与原版一致的 {@code IOException}/{@code JSONException} 抛出契约。
 * <p>
 * {@code -Dssoptimizer.disable.parallelspec=true} 时调用方应回退原版串行序列。
 */
public final class SpecLoadScheduler {
    private static final Logger LOGGER = Logger.getLogger(SpecLoadScheduler.class);

    /** 全局禁用开关。 */
    public static final String DISABLE_PROPERTY    = "ssoptimizer.disable.parallelspec";
    /** 并行度复用加载管线统一的 parallelism 属性。 */
    public static final String PARALLELISM_PROPERTY = "ssoptimizer.loading.parallelism";

    private SpecLoadScheduler() {
    }

    /** 并行 Spec 加载是否启用。 */
    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    /** DAG 与 Variant 解析共用的线程池并行度。 */
    public static int parallelism() {
        final int configured = Integer.getInteger(PARALLELISM_PROPERTY, 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    /**
     * 可抛出受检异常的 Spec 加载任务体。
     */
    @FunctionalInterface
    public interface SpecTask {
        void run() throws Exception;
    }

    /** 创建一张空 DAG。 */
    public static Dag newDag() {
        return new Dag();
    }

    /**
     * Spec 加载任务图。task 注册顺序无关，依赖按名字解析；{@link #join} 前
     * 必须注册完全部任务。
     */
    public static final class Dag {
        private final Map<String, CompletableFuture<Void>> tasks    = new LinkedHashMap<>();
        private final Map<String, SpecTask>                bodies   = new LinkedHashMap<>();
        private final Map<String, String[]>                depNames = new LinkedHashMap<>();

        private Dag() {
        }

        /**
         * 注册任务。
         *
         * @param name 任务名（日志与依赖引用用）
         * @param body 任务体
         * @param deps 依赖的任务名，全部完成后本任务才启动
         */
        public Dag task(final String name, final SpecTask body, final String... deps) {
            if (bodies.putIfAbsent(name, body) != null) {
                throw new IllegalArgumentException("重复注册 Spec 加载任务: " + name);
            }
            depNames.put(name, deps);
            return this;
        }

        /**
         * 并行执行整张图并等待完成。
         *
         * @throws IOException 任务体抛出的 IOException 原样重抛
         * @throws JSONException 任务体抛出的 JSONException 原样重抛
         */
        public void join() throws IOException, JSONException {
            final ExecutorService pool = Executors.newFixedThreadPool(parallelism(), runnable -> {
                final Thread thread = new Thread(runnable, "SSOptimizer-SpecLoad");
                thread.setDaemon(true);
                return thread;
            });
            final long startedAt = System.nanoTime();
            try {
                for (final Map.Entry<String, SpecTask> entry : bodies.entrySet()) {
                    tasks.put(entry.getKey(), schedule(pool, entry.getKey(), entry.getValue(), depNames.get(entry.getKey())));
                }
                CompletableFuture.allOf(tasks.values().toArray(new CompletableFuture[0])).join();
                LOGGER.info("[SSOptimizer] Parallel spec loading finished in "
                        + ((System.nanoTime() - startedAt) / 1_000_000L) + "ms with " + tasks.size() + " tasks");
            } catch (final CompletionException e) {
                Throwable cause = e.getCause();
                while (cause instanceof CompletionException && cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof IOException ioException) {
                    throw ioException;
                }
                if (cause instanceof JSONException jsonException) {
                    throw jsonException;
                }
                if (cause instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(cause);
            } finally {
                pool.shutdown();
            }
        }

        private CompletableFuture<Void> schedule(final ExecutorService pool,
                                                 final String name,
                                                 final SpecTask body,
                                                 final String[] deps) {
            final CompletableFuture<?>[] depFutures = new CompletableFuture[deps.length];
            for (int i = 0; i < deps.length; i++) {
                final CompletableFuture<Void> dep = tasks.get(deps[i]);
                if (dep == null) {
                    throw new IllegalArgumentException("任务 [" + name + "] 依赖了尚未注册的任务 [" + deps[i] + "]（注意注册顺序需先于依赖方）");
                }
                depFutures[i] = dep;
            }

            return CompletableFuture.allOf(depFutures).thenRunAsync(() -> {
                final long taskStartedAt = System.nanoTime();
                try {
                    body.run();
                    LOGGER.debug("[SSOptimizer] Spec task [" + name + "] finished in "
                            + ((System.nanoTime() - taskStartedAt) / 1_000_000L) + "ms");
                } catch (final Exception e) {
                    LOGGER.error("[SSOptimizer] Spec 加载任务 [" + name + "] 失败", e);
                    throw new CompletionException(e);
                }
            }, pool);
        }
    }
}
