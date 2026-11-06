package github.kasuminova.ssoptimizer.common.concurrent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SharedFrameWorkersTest {

    @Test
    void singletonReturnsSameInstance() {
        assertSame(SharedFrameWorkers.get(), SharedFrameWorkers.get(), "共享池必须全局唯一");
    }

    @Test
    void sharedPoolUsesSharedNamingAndResolvedThreadCount() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        assertEquals(SharedFrameWorkers.resolveThreadCount(System.getProperty(SharedFrameWorkers.THREADS_PROPERTY)),
                executor.threadCount(), "线程数必须与统一属性解析结果一致");

        final ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        executor.submit(() -> threadNames.add(Thread.currentThread().getName()), null);
        executor.awaitAll();
        assertEquals(1, threadNames.size());
        assertTrue(threadNames.peek().startsWith("SSOptimizer-Shared-Worker-"),
                "工作线程命名必须为 SSOptimizer-Shared-Worker-N，实际: " + threadNames.peek());
    }

    @Test
    void resolveThreadCountDefaultsToCoresMinusOne() {
        final int expected = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        assertEquals(expected, SharedFrameWorkers.resolveThreadCount(null), "未设置时默认 max(cores-1, 1)");
    }

    @Test
    void resolveThreadCountAcceptsValidOverride() {
        assertEquals(1, SharedFrameWorkers.resolveThreadCount("1"));
        assertEquals(3, SharedFrameWorkers.resolveThreadCount("3"));
    }

    @Test
    void resolveThreadCountFallsBackToDefaultOnInvalidValues() {
        final int expected = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        assertEquals(expected, SharedFrameWorkers.resolveThreadCount("0"), "0 非法，按默认处理");
        assertEquals(expected, SharedFrameWorkers.resolveThreadCount("-2"), "负数非法，按默认处理");
        assertEquals(expected, SharedFrameWorkers.resolveThreadCount("abc"), "不可解析，按默认处理");
        assertEquals(expected, SharedFrameWorkers.resolveThreadCount(""), "空串非法，按默认处理");
    }

    @Test
    void mixedKeyedAndUnkeyedTasksKeepStripeIsolation() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        final Object key = new Object();
        final ConcurrentLinkedQueue<String> keyedThreads = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<String> unkeyedThreads = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 16; i++) {
            executor.submit(() -> keyedThreads.add(Thread.currentThread().getName()), key);
            executor.submit(() -> unkeyedThreads.add(Thread.currentThread().getName()), null);
        }
        executor.awaitAll();
        assertEquals(16, keyedThreads.size());
        assertEquals(16, unkeyedThreads.size());
        assertEquals(1, keyedThreads.stream().distinct().count(), "相同 stripeKey 必须在同一 worker 串行");
    }

    @Test
    void distinctStripeKeysSpreadAcrossSharedWorkers() {
        final FrameParallelExecutor executor = SharedFrameWorkers.get();
        final int workers = executor.threadCount();
        if (workers < 2) {
            executor.submit(() -> { }, null);
            executor.awaitAll();
            return;
        }
        final ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        final CountDownLatch entered = new CountDownLatch(2);
        final Object key0 = keyForStripe(0, workers);
        final Object key1 = keyForStripe(1 % workers, workers);
        for (final Object key : new Object[]{key0, key1}) {
            executor.submit(() -> {
                threadNames.add(Thread.currentThread().getName());
                entered.countDown();
                try {
                    assertTrue(entered.await(10, TimeUnit.SECONDS), "不同键任务必须并行执行");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fail("interrupted");
                }
            }, key);
        }
        executor.awaitAll();
        assertEquals(2, threadNames.stream().distinct().count(), "不同 stripeKey 必须落到不同 worker");
    }

    @Test
    void isWorkerThreadGuardRecognizesSharedWorkers() {
        assertFalse(SharedFrameWorkers.isWorkerThread(), "主线程不得被识别为工作线程");
        final AtomicBoolean onWorker = new AtomicBoolean(false);
        SharedFrameWorkers.get().submit(() -> onWorker.set(SharedFrameWorkers.isWorkerThread()), null);
        SharedFrameWorkers.get().awaitAll();
        assertTrue(onWorker.get(), "共享池工作线程必须被守卫识别");
    }

    private static Object keyForStripe(final int stripe, final int mod) {
        for (int i = 0; i < 100000; i++) {
            final Object candidate = new Object();
            if ((System.identityHashCode(candidate) & 0x7fffffff) % mod == stripe) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot find key for stripe " + stripe);
    }
}
