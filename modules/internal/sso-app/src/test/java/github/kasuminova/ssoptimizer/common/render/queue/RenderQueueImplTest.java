package github.kasuminova.ssoptimizer.common.render.queue;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RenderQueueImplTest {

    private RenderQueueImpl queue;

    @AfterEach
    void shutdown() {
        if (queue != null) {
            queue.shutdown();
        }
        RenderThreadMode.resetLoadingFinishedForTesting();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted");
        }
    }

    @Test
    void executesCommandsInSubmissionOrderOnRenderThread() {
        queue = new RenderQueueImpl();
        List<Integer> executed = new CopyOnWriteArrayList<>();
        ConcurrentLinkedQueue<String> threadNames = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < 100; i++) {
            final int value = i;
            queue.submit(() -> {
                executed.add(value);
                threadNames.add(Thread.currentThread().getName());
            });
        }
        queue.swapFrames();
        queue.swapFramesAndSync();
        assertEquals(100, executed.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(i, executed.get(i));
        }
        assertTrue(threadNames.stream().allMatch(RenderQueueImpl.RENDER_THREAD_NAME::equals));
    }

    @Test
    void swapFramesAndSyncBlocksUntilPreviousFrameCompletes() throws Exception {
        queue = new RenderQueueImpl();
        CountDownLatch frame1Gate = new CountDownLatch(1);
        CountDownLatch frame1Started = new CountDownLatch(1);
        queue.submit(() -> {
            frame1Started.countDown();
            awaitLatch(frame1Gate);
        });
        queue.swapFrames();

        AtomicBoolean swapReturned = new AtomicBoolean(false);
        Thread swapper = new Thread(() -> {
            queue.swapFramesAndSync();
            swapReturned.set(true);
        });
        swapper.start();
        awaitLatch(frame1Started);
        swapper.join(300);
        assertTrue(swapper.isAlive());
        assertFalse(swapReturned.get());
        frame1Gate.countDown();
        swapper.join(10_000);
        assertFalse(swapper.isAlive());
        assertTrue(swapReturned.get());
    }

    @Test
    void swapFramesAndSyncDoesNotWaitCurrentFrame() throws Exception {
        queue = new RenderQueueImpl();
        queue.submit(() -> {
        });
        CountDownLatch frame2Gate = new CountDownLatch(1);
        CountDownLatch frame2Started = new CountDownLatch(1);
        AtomicBoolean swapReturned = new AtomicBoolean(false);
        Thread swapper = new Thread(() -> {
            queue.submit(() -> {
                frame2Started.countDown();
                awaitLatch(frame2Gate);
            });
            queue.swapFramesAndSync();
            swapReturned.set(true);
        });
        swapper.start();
        awaitLatch(frame2Started);
        swapper.join(10_000);
        assertFalse(swapper.isAlive());
        assertTrue(swapReturned.get());
        frame2Gate.countDown();
        queue.swapFramesAndSync();
    }

    @Test
    void renderThreadFailureRethrownOnNextSwap() {
        queue = new RenderQueueImpl();
        queue.submit(() -> {
            throw new IllegalArgumentException("boom");
        });
        queue.swapFrames();
        IllegalStateException ex = assertThrows(IllegalStateException.class, queue::swapFramesAndSync);
        assertTrue(String.valueOf(ex.getMessage()).contains("渲染线程执行上一帧命令失败"));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
        assertEquals("boom", ex.getCause().getMessage());
    }

    @Test
    void frameFailureHookRunsOnCommandFailure() {
        queue = new RenderQueueImpl();
        AtomicInteger hookCalls = new AtomicInteger();
        queue.setFrameFailureHook(hookCalls::incrementAndGet);
        queue.submit(() -> {
            throw new IllegalArgumentException("boom");
        });
        queue.swapFrames();
        assertThrows(IllegalStateException.class, queue::swapFramesAndSync);
        assertEquals(1, hookCalls.get(), "帧命令失败必须触发一次帧失败钩子");

        queue.submit(() -> {
        });
        queue.swapFramesAndSync();
        assertEquals(1, hookCalls.get());
    }

    @Test
    void multiProducerSubmitIsThreadSafe() throws Exception {
        queue = new RenderQueueImpl();
        int producers = 4;
        int perProducer = 250;
        AtomicInteger executed = new AtomicInteger();
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            threads[p] = new Thread(() -> {
                for (int i = 0; i < perProducer; i++) {
                    queue.submit(executed::incrementAndGet);
                }
            });
        }
        for (Thread t : threads) {
            t.start();
        }
        for (Thread t : threads) {
            t.join(10_000);
            assertFalse(t.isAlive());
        }
        queue.swapFrames();
        queue.swapFramesAndSync();
        assertEquals(producers * perProducer, executed.get());
    }

    @Test
    void getRunsOnRenderThreadAndReturnsValue() {
        queue = new RenderQueueImpl();
        String threadName = queue.get(() -> Thread.currentThread().getName());
        assertEquals(RenderQueueImpl.RENDER_THREAD_NAME, threadName);
        AtomicBoolean ran = new AtomicBoolean(false);
        queue.wait(() -> ran.set(true));
        assertTrue(ran.get());
    }

    @Test
    void getOnRenderThreadExecutesDirectly() {
        queue = new RenderQueueImpl();
        AtomicBoolean nestedResult = new AtomicBoolean(false);
        queue.submit(() -> nestedResult.set(queue.get(() -> true)));
        queue.swapFrames();
        assertDoesNotThrow(() -> queue.swapFramesAndSync());
        assertTrue(nestedResult.get());
    }

    @Test
    void blockingCallsCountedByStallDetector() {
        StallDetector detector = new StallDetector(60, 2);
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), detector);
        RenderThreadMode.markLoadingFinished();
        queue.get(() -> null);
        queue.swapFramesAndSync();
        assertDoesNotThrow(() -> queue.get(() -> null));
        assertEquals(2, detector.currentWindowStalls());
    }

    @Test
    void loadingPhaseBlockingCallsAreExemptFromStallDetector() {
        StallDetector detector = new StallDetector(60, 2);
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), detector);
        queue.get(() -> null);
        queue.get(() -> null);
        assertEquals(0, detector.currentWindowStalls(), "加载期阻塞调用不得计入熔断窗口");
        RenderThreadMode.markLoadingFinished();
        queue.get(() -> null);
        assertEquals(1, detector.currentWindowStalls(), "加载期结束后恢复计数");
        queue.swapFramesAndSync();
        assertDoesNotThrow(() -> queue.get(() -> null));
        assertEquals(2, detector.currentWindowStalls());
    }

    @Test
    void resourceBlockingCallsAreExemptFromStallDetector() {
        StallDetector detector = new StallDetector(60, 2);
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), detector);
        RenderThreadMode.markLoadingFinished();
        queue.getUncounted(() -> null);
        queue.waitUncounted(() -> {
        });
        queue.swapFramesAndSync();
        queue.getUncounted(() -> null);
        queue.swapFramesAndSync();
        queue.getUncounted(() -> null);
        assertEquals(0, detector.currentWindowStalls(), "资源申请类调用任何时期都不得计入熔断窗口");
        queue.swapFramesAndSync();
        queue.get(() -> null);
        assertEquals(1, detector.currentWindowStalls(), "回读类调用保持计数");
        queue.swapFramesAndSync();
        assertDoesNotThrow(() -> queue.get(() -> null));
        assertEquals(2, detector.currentWindowStalls());
    }

    @Test
    void framesAreReturnedToPoolAfterExecution() {
        FramePool pool = new FramePool(FramePool.DEFAULT_CAPACITY);
        queue = new RenderQueueImpl(pool, new StallDetector());
        queue.swapFrames();
        queue.swapFramesAndSync();
        queue.swapFramesAndSync();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (pool.idleCount() < 1 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(pool.idleCount() >= 1);
    }

    @Test
    void multiProducerSyncCallsAndFrameCommandsAllComplete() throws InterruptedException {
        queue = new RenderQueueImpl();
        int producers = 4;
        int perProducer = 200;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger syncErrors = new AtomicInteger();
        Thread[] threads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            final int base = p * perProducer;
            threads[p] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perProducer; i++) {
                    int expected = base + i;
                    if (queue.get(() -> expected) != expected) {
                        syncErrors.incrementAndGet();
                    }
                    queue.submit(executed::incrementAndGet);
                }
            }, "aux-producer-" + p);
            threads[p].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(thread.isAlive(), "生产者线程未在期限内完成：" + thread.getName());
        }
        while (executed.get() < producers * perProducer) {
            queue.swapFrames();
            queue.swapFramesAndSync();
        }
        queue.swapFramesAndSync();
        assertEquals(0, syncErrors.get(), "同步任务结果必须原样返回");
        assertEquals(producers * perProducer, executed.get(), "帧命令必须全部执行");
    }

    @Test
    void mergedBatchCommandsExecuteAsConsecutiveRun() {
        queue = new RenderQueueImpl();
        List<String> events = new CopyOnWriteArrayList<>();
        class FakeBatch implements MergedBatchCommand {
            private final int id;

            FakeBatch(int id) {
                this.id = id;
            }

            @Override
            public void executeMerged(boolean runHead, boolean runTail) {
                events.add(id + ":" + (runHead ? "H" : "-") + (runTail ? "T" : "-"));
            }
        }
        queue.submit(new FakeBatch(0));
        queue.submit(new FakeBatch(1));
        queue.submit(() -> events.add("mid"));
        queue.submit(new FakeBatch(2));
        queue.swapFrames();
        queue.swapFramesAndSync();
        assertEquals(List.of("0:H-", "1:-T", "mid", "2:HT"), events);
    }

    @Test
    void frameProbeDrainsErrorsOncePerFrame() {
        List<Integer> scripted = new ArrayList<>(List.of(1281, 0));
        AtomicInteger sourceCalls = new AtomicInteger();
        IntSupplier source = () -> {
            sourceCalls.incrementAndGet();
            return scripted.isEmpty() ? 0 : scripted.remove(0);
        };
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(),
                RenderQueueImpl.GlErrorProbe.FRAME, source);
        queue.submit(() -> { });
        queue.submit(() -> { });
        queue.swapFrames();
        queue.get(() -> null);
        assertEquals(2, sourceCalls.get(), "帧模式应在帧尾恰好排空一次（1281 + 终止 0）");
    }

    @Test
    void commandProbeDrainsAfterEachCommand() {
        AtomicInteger sourceCalls = new AtomicInteger();
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(),
                RenderQueueImpl.GlErrorProbe.COMMAND, () -> {
            sourceCalls.incrementAndGet();
            return 0;
        });
        queue.submit(() -> { });
        queue.submit(() -> { });
        queue.swapFrames();
        queue.get(() -> null);
        assertEquals(2, sourceCalls.get(), "命令模式应在每条命令后各取一次错误码");
    }

    @Test
    void probeOffDoesNotTouchErrorSource() {
        AtomicInteger sourceCalls = new AtomicInteger();
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(),
                RenderQueueImpl.GlErrorProbe.OFF, () -> {
            sourceCalls.incrementAndGet();
            return 0;
        });
        queue.submit(() -> { });
        queue.swapFrames();
        queue.get(() -> null);
        assertEquals(0, sourceCalls.get(), "探针关闭时不得触碰错误源");
    }

    @Test
    void probeRejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> RenderQueueImpl.GlErrorProbe.parse("verbose"));
        assertEquals(RenderQueueImpl.GlErrorProbe.FRAME, RenderQueueImpl.GlErrorProbe.parse(" Frame "));
    }

    @Test
    void stallTripWarnPolicyServesCallWithoutThrowing() {
        RenderThreadMode.markLoadingFinished();
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(2, 1));
        assertEquals(42, (int) queue.get(() -> 42));
        assertEquals(43, (int) queue.get(() -> 43), "持续击穿期间后续调用仍须正常服务");
    }

    @Test
    void stallTripCrashPolicyThrows() {
        RenderThreadMode.markLoadingFinished();
        System.setProperty(RenderQueueImpl.STALL_POLICY_PROPERTY, "crash");
        try {
            queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(2, 1));
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> queue.get(() -> 42));
            assertTrue(String.valueOf(ex.getMessage()).contains("top5"));
        } finally {
            System.clearProperty(RenderQueueImpl.STALL_POLICY_PROPERTY);
        }
    }

    @Test
    void stallPolicyParsing() {
        assertTrue(RenderQueueImpl.parseStallTripCrash("crash"));
        assertTrue(RenderQueueImpl.parseStallTripCrash(" CRASH ".trim()));
        assertFalse(RenderQueueImpl.parseStallTripCrash("warn"));
        assertFalse(RenderQueueImpl.parseStallTripCrash("verbose"), "非法值按 warn 处理");
    }
}
