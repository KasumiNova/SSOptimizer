package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.concurrent.VtWorkers;
import org.apache.log4j.Logger;
import org.json.JSONException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;

/**
 * Spec 加载 DAG 并行调度器。
 * <p>
 * 把 {@code SpecStore.loadStarmap} 的扁平串行调用序列重组为带依赖声明的任务图：
 * 无依赖或依赖已完成的任务在 {@link VtWorkers} 虚拟线程上并行执行，依赖未完成时自动等待。
 * 任务体异常会被包装携带任务名后提前终止整张图，并在 barrier 处解包重抛，
 * 保持与原版一致的 {@code IOException}/{@code JSONException} 抛出契约。
 * <p>
 * 线程模型（Wave 3 起）：任务跑在虚拟线程上，{@link #PARALLELISM_PROPERTY} 保留为
 * 每次 {@link Dag#join()} 的 Semaphore 最大并发闸门——spec 加载任务是 CPU 密集的
 * JSON/CSV 解析，无闸门时整图 30+ 任务瞬时并发只会空转争抢载体线程与磁盘带宽，
 * 闸门保留原「加载期并行度调谐旋钮」语义。
 * <p>
 * {@code -Dssoptimizer.disable.parallelspec=true} 时调用方应回退原版串行序列。
 */
public final class SpecLoadScheduler {
    private static final Logger LOGGER = Logger.getLogger(SpecLoadScheduler.class);

    /** 全局禁用开关。 */
    public static final String DISABLE_PROPERTY    = "ssoptimizer.disable.parallelspec";
    /** 并行度复用加载管线统一的 parallelism 属性（Wave 3 起语义为 Semaphore 并发闸门许可数）。 */
    public static final String PARALLELISM_PROPERTY = "ssoptimizer.loading.parallelism";

    private SpecLoadScheduler() {
    }

    /** 并行 Spec 加载是否启用。 */
    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    /** DAG 与 Variant 解析共用的并发闸门许可数。 */
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
            final Semaphore gate = new Semaphore(parallelism());
            final long startedAt = System.nanoTime();
            try {
                for (final Map.Entry<String, SpecTask> entry : bodies.entrySet()) {
                    tasks.put(entry.getKey(), schedule(gate, entry.getKey(), entry.getValue(), depNames.get(entry.getKey())));
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
            }
        }

        private CompletableFuture<Void> schedule(final Semaphore gate,
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
                try {
                    gate.acquire();
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(e);
                }
                final long taskStartedAt = System.nanoTime();
                try {
                    body.run();
                    LOGGER.debug("[SSOptimizer] Spec task [" + name + "] finished in "
                            + ((System.nanoTime() - taskStartedAt) / 1_000_000L) + "ms");
                } catch (final Exception e) {
                    LOGGER.error("[SSOptimizer] Spec 加载任务 [" + name + "] 失败", e);
                    throw new CompletionException(e);
                } finally {
                    gate.release();
                }
            }, VtWorkers::submit);
        }
    }
}
