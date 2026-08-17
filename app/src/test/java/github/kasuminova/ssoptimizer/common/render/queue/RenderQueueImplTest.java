package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenderQueueImplTest {

    private RenderQueueImpl queue;

    @AfterEach
    void shutdown() {
        if (queue != null) {
            queue.shutdown();
        }
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
        // 第二次 swap 等待的正是上面提交的那一帧
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
        // 第 1 帧：阻塞渲染线程直到测试放行
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
        // 上一帧（第 1 帧）未完成 → swap 线程必须仍阻塞
        swapper.join(300);
        assertTrue(swapper.isAlive());
        assertFalse(swapReturned.get());
        // 放行后 swap 线程通过
        frame1Gate.countDown();
        swapper.join(10_000);
        assertFalse(swapper.isAlive());
        assertTrue(swapReturned.get());
    }

    @Test
    void swapFramesAndSyncDoesNotWaitCurrentFrame() throws Exception {
        queue = new RenderQueueImpl();
        // 第 1 帧：立即完成
        queue.submit(() -> {
        });
        CountDownLatch frame2Gate = new CountDownLatch(1);
        CountDownLatch frame2Started = new CountDownLatch(1);
        AtomicBoolean swapReturned = new AtomicBoolean(false);
        Thread swapper = new Thread(() -> {
            // 提交第 2 帧（内含阻塞命令），只应等待已完成的第 1 帧而立即返回
            queue.submit(() -> {
                frame2Started.countDown();
                awaitLatch(frame2Gate);
            });
            queue.swapFramesAndSync();
            swapReturned.set(true);
        });
        swapper.start();
        awaitLatch(frame2Started);
        // 渲染线程已阻塞在第 2 帧命令内，但 swap 只等第 1 帧 → 必须已返回
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
        // 渲染线程内再次 get 必须直接执行，否则自死锁
        queue.submit(() -> nestedResult.set(queue.get(() -> true)));
        queue.swapFrames();
        assertDoesNotThrow(() -> queue.swapFramesAndSync());
        assertTrue(nestedResult.get());
    }

    @Test
    void blockingCallsCountedByStallDetector() {
        queue = new RenderQueueImpl(new FramePool(FramePool.DEFAULT_CAPACITY), new StallDetector(60, 2));
        queue.get(() -> null);
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> queue.get(() -> null));
        assertTrue(String.valueOf(ex.getMessage()).contains("阻塞式 GL 调用"));
    }

    @Test
    void framesAreReturnedToPoolAfterExecution() {
        FramePool pool = new FramePool(FramePool.DEFAULT_CAPACITY);
        queue = new RenderQueueImpl(pool, new StallDetector());
        queue.swapFrames();
        queue.swapFramesAndSync();
        queue.swapFramesAndSync();
        // 两帧均已执行完并归还：池内至少有归还的帧
        assertTrue(pool.idleCount() >= 1);
    }
}
