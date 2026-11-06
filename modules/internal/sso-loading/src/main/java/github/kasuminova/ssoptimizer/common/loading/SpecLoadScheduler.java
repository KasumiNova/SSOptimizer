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

public final class SpecLoadScheduler {
    private static final Logger LOGGER = Logger.getLogger(SpecLoadScheduler.class);

    public static final String DISABLE_PROPERTY    = "ssoptimizer.disable.parallelspec";
    public static final String PARALLELISM_PROPERTY = "ssoptimizer.loading.parallelism";

    private SpecLoadScheduler() {
    }

    public static boolean isEnabled() {
        return !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    public static int parallelism() {
        final int configured = Integer.getInteger(PARALLELISM_PROPERTY, 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    @FunctionalInterface
    public interface SpecTask {
        void run() throws Exception;
    }

    public static Dag newDag() {
        return new Dag();
    }

    public static final class Dag {
        private final Map<String, CompletableFuture<Void>> tasks    = new LinkedHashMap<>();
        private final Map<String, SpecTask>                bodies   = new LinkedHashMap<>();
        private final Map<String, String[]>                depNames = new LinkedHashMap<>();

        private Dag() {
        }

        public Dag task(final String name, final SpecTask body, final String... deps) {
            if (bodies.putIfAbsent(name, body) != null) {
                throw new IllegalArgumentException("重复注册 Spec 加载任务: " + name);
            }
            depNames.put(name, deps);
            return this;
        }

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
