package github.kasuminova.ssoptimizer.loading;

import org.apache.log4j.Logger;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts multiple instances of the base game's deferred image worker instead of
 * the original single background thread. This keeps the game's queue/result-map
 * protocol intact while parallelizing independent ImageIO decode work.
 */
public final class ParallelImagePreloadCoordinator {
    static final String DEFAULT_WORKER_CLASS  = "github.kasuminova.ssoptimizer.loading.ParallelImagePreloadWorker";
    static final String WORKER_CLASS_PROPERTY = "ssoptimizer.loading.workerClass";
    static final String PARALLELISM_PROPERTY  = "ssoptimizer.loading.parallelism";
    static final String DISABLE_PROPERTY      = "ssoptimizer.disable.parallelpreload";

    private static final Logger       LOGGER  = Logger.getLogger(ParallelImagePreloadCoordinator.class);
    private static final List<Thread> WORKERS = new ArrayList<>();

    private ParallelImagePreloadCoordinator() {
    }

    public static synchronized void startWorkers() {
        reapStoppedWorkers();
        if (!WORKERS.isEmpty()) {
            return;
        }

        int parallelism = configuredParallelism();
        for (int i = 0; i < parallelism; i++) {
            Thread worker = new Thread(newWorkerRunnable(), "SSOptimizer-ImagePreload-" + (i + 1));
            worker.setDaemon(true);
            WORKERS.add(worker);
            worker.start();
        }

        LOGGER.info("[SSOptimizer] Started " + parallelism + " deferred image preload worker(s)");
    }

    public static synchronized void stopWorkers() {
        for (Thread worker : WORKERS) {
            worker.interrupt();
        }
        WORKERS.clear();
    }

    static synchronized int activeWorkerCount() {
        reapStoppedWorkers();
        return WORKERS.size();
    }

    private static Runnable newWorkerRunnable() {
        try {
            String className = System.getProperty(WORKER_CLASS_PROPERTY, DEFAULT_WORKER_CLASS);
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> workerClass = Class.forName(className, true, loader);
            if (!Runnable.class.isAssignableFrom(workerClass)) {
                throw new IllegalStateException(className + " does not implement Runnable");
            }

            Constructor<?> constructor = workerClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (Runnable) constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to construct deferred image preload worker", e);
        }
    }

    private static int configuredParallelism() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return 1;
        }

        int configured = Integer.getInteger(PARALLELISM_PROPERTY, defaultParallelism());
        return Math.max(1, configured);
    }

    private static int defaultParallelism() {
        return Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    }

    private static void reapStoppedWorkers() {
        WORKERS.removeIf(worker -> !worker.isAlive());
    }
}