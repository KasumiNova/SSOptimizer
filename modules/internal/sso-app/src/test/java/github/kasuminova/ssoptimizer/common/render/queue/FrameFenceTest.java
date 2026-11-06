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
    void signalIsSignaledLifecycle() {
        FrameFence fence = new FrameFenceImpl();
        assertFalse(fence.isSignaled());
        fence.signal();
        assertTrue(fence.isSignaled());
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

    @Test
    void waitOnSignaledFencePassesImmediately() {
        FrameFence fence = new FrameFenceImpl();
        fence.signal();
        assertDoesNotThrow(() -> new WaitFenceCommand(fence).execute());
    }

    @Test
    void waitOnUnsignaledFenceThrowsSuspendFrame() {
        FrameFence fence = new FrameFenceImpl();
        WaitFenceCommand wait = new WaitFenceCommand(fence);
        assertThrows(SuspendFrameException.class, wait::execute);
        fence.signal();
        assertDoesNotThrow(wait::execute);
    }

    @Test
    void unsignaledWaitSuspendsFrameAndContinuationFinishesAfterSignal() throws Exception {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        AtomicBoolean waitPassed = new AtomicBoolean(false);
        queue.submit(new WaitFenceCommand(fence));
        queue.submit(() -> waitPassed.set(true));
        queue.swapFrames();
        queue.swapFramesAndSync();
        assertFalse(waitPassed.get(), "fence 未 signal 时悬挂点后的命令不得执行");
        fence.signal();
        long deadline = System.currentTimeMillis() + 5000;
        while (!waitPassed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(waitPassed.get(), "fence signal 后续跑任务必须执行完余下命令");
    }

    @Test
    void suspendedFrameDoesNotBlockSubsequentFrames() {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        queue.submit(new WaitFenceCommand(fence));
        queue.swapFrames();
        AtomicBoolean laterFrameRan = new AtomicBoolean(false);
        queue.submit(() -> laterFrameRan.set(true));
        queue.swapFrames();
        queue.swapFramesAndSync();
        queue.swapFramesAndSync();
        assertTrue(laterFrameRan.get(), "悬挂帧不得阻塞后续帧的执行");
        assertFalse(fence.isSignaled());
    }

    @Test
    void frameFailureForceSignalsRegisteredFences() {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        queue.currentFrame().addFence(fence);
        queue.submit(() -> {
            throw new RuntimeException("boom");
        });
        queue.swapFrames();
        assertThrows(IllegalStateException.class, queue::swapFramesAndSync);
        assertTrue(fence.isSignaled(), "失败帧登记的 fence 必须被强制 signal");
    }
}
