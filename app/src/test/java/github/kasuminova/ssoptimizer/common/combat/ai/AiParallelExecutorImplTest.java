package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AiParallelExecutorImplTest {

    @Test
    void runsTasksOnWorkerThreads() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
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
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(4);
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
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
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
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(2);
        AtomicInteger completed = new AtomicInteger();
        executor.submit(completed::incrementAndGet, null);
        executor.submit(() -> {
            throw new IllegalStateException("boom");
        }, null);
        executor.submit(completed::incrementAndGet, null);
        RuntimeException ex = assertThrows(RuntimeException.class, executor::awaitAll);
        assertTrue(String.valueOf(ex.getMessage()).contains("Parallel ship AI failed"));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
        assertEquals(2, completed.get());
        // 异常清理后下一帧可正常工作
        executor.submit(completed::incrementAndGet, null);
        assertDoesNotThrow(executor::awaitAll);
        assertEquals(3, completed.get());
    }

    @Test
    void isWorkerThreadOnlyTrueOnWorkers() {
        AiParallelExecutorImpl executor = new AiParallelExecutorImpl(1);
        assertFalse(executor.isWorkerThread());
        boolean[] onWorker = {false};
        executor.submit(() -> onWorker[0] = executor.isWorkerThread(), null);
        executor.awaitAll();
        assertTrue(onWorker[0]);
    }

    @Test
    void rejectsInvalidThreadCount() {
        assertThrows(IllegalArgumentException.class, () -> new AiParallelExecutorImpl(0));
    }
}
