package github.kasuminova.ssoptimizer.common.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FrameParallelExecutorImplTest {

    @Test
    void runsTasksOnWorkerThreads() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 2);
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 8; i++) {
            executor.submit(() -> threadNames.add(Thread.currentThread().getName()), null);
        }
        executor.awaitAll();
        assertEquals(8, threadNames.size());
        assertTrue(threadNames.stream().allMatch(n -> n.startsWith("SSOptimizer-AI-Worker-")));
        assertTrue(executor.threadCount() == 2);
    }

    @Test
    void sameStripeKeyRunsOnSameThread() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 4);
        Object key = new Object();
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 16; i++) {
            executor.submit(() -> threadNames.add(Thread.currentThread().getName()), key);
        }
        executor.awaitAll();
        assertEquals(1, threadNames.stream().distinct().count());
    }

    @Test
    void distinctStripeKeysMaySpreadAcrossWorkers() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 2);
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        CountDownLatch entered = new CountDownLatch(2);
        // 确定性选择落在不同 worker 的两个分组键（身份哈希奇偶各一）
        Object key0 = keyForStripe(0, 2);
        Object key1 = keyForStripe(1, 2);
        for (Object key : new Object[]{key0, key1}) {
            executor.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                entered.countDown();
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }, key);
        }
        executor.awaitAll();
        // 两个任务并行完成（若落在同一 worker 串行执行，entered.await 会超时失败）
        assertEquals(2, threadNames.size());
        assertEquals(2, threadNames.stream().distinct().count());
    }

    private static Object keyForStripe(int stripe, int mod) {
        for (int i = 0; i < 10000; i++) {
            Object candidate = new Object();
            if ((System.identityHashCode(candidate) & 0x7fffffff) % mod == stripe) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot find key for stripe " + stripe);
    }

    @Test
    void awaitAllRethrowsTaskFailureOnCallerThread() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 2);
        AtomicInteger completed = new AtomicInteger();
        executor.submit(completed::incrementAndGet, null);
        executor.submit(() -> {
            throw new IllegalStateException("boom");
        }, null);
        executor.submit(completed::incrementAndGet, null);
        RuntimeException ex = assertThrows(RuntimeException.class, executor::awaitAll);
        assertTrue(String.valueOf(ex.getMessage()).contains("Parallel AI failed"));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(2, completed.get());
        // 异常清理后下一帧可正常工作
        executor.submit(completed::incrementAndGet, null);
        assertDoesNotThrow(executor::awaitAll);
        assertEquals(3, completed.get());
    }

    @Test
    void isWorkerThreadOnlyTrueOnWorkers() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 1);
        assertFalse(executor.isWorkerThread());
        boolean[] onWorker = {false};
        executor.submit(() -> onWorker[0] = executor.isWorkerThread(), null);
        executor.awaitAll();
        assertTrue(onWorker[0]);
    }

    @Test
    void successfulPooledTasksAreRecycledOnWorker() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 1);
        AtomicInteger recycled = new AtomicInteger();
        executor.submit(new TrackingPooledTask(recycled, false), null);
        executor.awaitAll();
        assertEquals(1, recycled.get(), "成功任务必须由工作线程归还池");
    }

    @Test
    void failedPooledTasksAreNotRecycledUntilRerun() {
        FrameParallelExecutorImpl executor = new FrameParallelExecutorImpl("AI", 1);
        AtomicInteger recycled = new AtomicInteger();
        // 首次执行抛异常：失败任务不归还（主线程重跑时字段必须保持原值）；
        // 重跑在主线程直接 run()，同样不触发 recycle
        executor.submit(new TrackingPooledTask(recycled, true), null);
        assertDoesNotThrow(executor::awaitAll);
        assertEquals(0, recycled.get(), "失败任务不得归还池");
    }

    @Test
    void rejectsInvalidThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> new FrameParallelExecutorImpl("AI", 0));
    }

    /** 池化任务测试桩：记录 recycle 次数；failFirst 时首次 run 抛异常。 */
    private static final class TrackingPooledTask implements Runnable, FrameParallelExecutor.PooledTask {
        private final AtomicInteger recycled;
        private final boolean failFirst;
        private int runs;

        private TrackingPooledTask(final AtomicInteger recycled, final boolean failFirst) {
            this.recycled = recycled;
            this.failFirst = failFirst;
        }

        @Override
        public void run() {
            if (failFirst && runs++ == 0) {
                throw new IllegalStateException("simulated concurrent failure");
            }
        }

        @Override
        public void recycle() {
            recycled.incrementAndGet();
        }
    }
}
