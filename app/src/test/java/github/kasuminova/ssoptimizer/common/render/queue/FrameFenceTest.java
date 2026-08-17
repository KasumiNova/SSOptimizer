package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class FrameFenceTest {

    private RenderQueueImpl queue;

    @AfterEach
    void shutdown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void signalAwaitIsSignaledLifecycle() throws Exception {
        FrameFence fence = new FrameFenceImpl();
        assertFalse(fence.isSignaled());
        Thread waiter = new Thread(() -> {
            try {
                fence.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrupted");
            }
        });
        waiter.start();
        fence.signal();
        waiter.join(10_000);
        assertFalse(waiter.isAlive());
        assertTrue(fence.isSignaled());
        // signal 幂等
        assertDoesNotThrow(fence::signal);
        assertTrue(fence.isSignaled());
    }

    @Test
    void signalThenWaitInSameFrameCompletesInOrder() {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        AtomicBoolean done = new AtomicBoolean(false);
        queue.submit(new SignalFenceCommand(fence));
        queue.submit(new WaitFenceCommand(fence));
        queue.submit(() -> done.set(true));
        queue.swapFrames();
        assertDoesNotThrow(() -> queue.swapFramesAndSync());
        assertTrue(fence.isSignaled());
        assertTrue(done.get());
    }

    /**
     * BoxUtil 场景：glWaitSync 先于 fence 信号被提交到渲染流。信号最终来自
     * 渲染队列之外（CPU 侧生产者线程直接 signal），渲染线程阻塞等待不得死锁。
     */
    @Test
    void waitSubmittedBeforeSignalDoesNotDeadlock() throws Exception {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        AtomicBoolean waitPassed = new AtomicBoolean(false);
        queue.submit(new WaitFenceCommand(fence));
        queue.submit(() -> waitPassed.set(true));
        queue.swapFrames();
        // 渲染线程应阻塞在 WaitFenceCommand 内
        Thread.sleep(200);
        assertFalse(waitPassed.get());
        assertFalse(fence.isSignaled());
        // CPU 侧（模拟 BoxUtil 生产者线程的协调点）完成 fence，渲染线程放行
        fence.signal();
        queue.swapFramesAndSync();
        assertTrue(waitPassed.get());
    }
}
