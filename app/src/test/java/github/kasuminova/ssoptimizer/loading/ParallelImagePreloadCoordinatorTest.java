package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelImagePreloadCoordinatorTest {
    @AfterEach
    void tearDown() {
        ParallelImagePreloadCoordinator.stopWorkers();
        System.clearProperty(ParallelImagePreloadCoordinator.WORKER_CLASS_PROPERTY);
        System.clearProperty(ParallelImagePreloadCoordinator.PARALLELISM_PROPERTY);
        System.clearProperty(ParallelImagePreloadCoordinator.DISABLE_PROPERTY);
        FakeWorker.reset(0);
    }

    @Test
    void startsConfiguredNumberOfWorkers() throws Exception {
        FakeWorker.reset(2);
        System.setProperty(ParallelImagePreloadCoordinator.WORKER_CLASS_PROPERTY, FakeWorker.class.getName());
        System.setProperty(ParallelImagePreloadCoordinator.PARALLELISM_PROPERTY, "2");

        ParallelImagePreloadCoordinator.startWorkers();

        assertTrue(FakeWorker.awaitStarted(), "Configured workers should all start");
        assertEquals(2, ParallelImagePreloadCoordinator.activeWorkerCount());

        ParallelImagePreloadCoordinator.stopWorkers();
        assertTrue(FakeWorker.awaitInterrupted(), "Stopping workers should interrupt each worker thread");
    }

    @Test
    void disableFlagFallsBackToSingleWorker() throws Exception {
        FakeWorker.reset(1);
        System.setProperty(ParallelImagePreloadCoordinator.WORKER_CLASS_PROPERTY, FakeWorker.class.getName());
        System.setProperty(ParallelImagePreloadCoordinator.DISABLE_PROPERTY, "true");

        ParallelImagePreloadCoordinator.startWorkers();

        assertTrue(FakeWorker.awaitStarted(), "Disabled parallel preload should still start one compatibility worker");
        assertEquals(1, ParallelImagePreloadCoordinator.activeWorkerCount());
    }

    public static final class FakeWorker implements Runnable {
        private static volatile CountDownLatch started     = new CountDownLatch(0);
        private static volatile CountDownLatch interrupted = new CountDownLatch(0);

        static void reset(int count) {
            started = new CountDownLatch(count);
            interrupted = new CountDownLatch(count);
        }

        static boolean awaitStarted() throws InterruptedException {
            return started.await(2, TimeUnit.SECONDS);
        }

        static boolean awaitInterrupted() throws InterruptedException {
            return interrupted.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void run() {
            started.countDown();
            try {
                while (true) {
                    Thread.sleep(1000L);
                }
            } catch (InterruptedException ignored) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }
    }
}