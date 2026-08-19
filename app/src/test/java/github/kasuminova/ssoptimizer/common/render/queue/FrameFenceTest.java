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
        // fence 到达后同一命令实例放行（续跑任务复用原命令对象）
        fence.signal();
        assertDoesNotThrow(wait::execute);
    }

    /**
     * BoxUtil 三方死锁场景：fence 信号滞后于 wait 执行（生产者被 Phaser 挡住，
     * 信号要等主线程推进后才会到来）。悬挂协议下渲染线程不得阻塞——本帧必须
     * 正常完成释放主线程，余下命令由续跑任务在 fence signal 后执行。
     */
    @Test
    void unsignaledWaitSuspendsFrameAndContinuationFinishesAfterSignal() throws Exception {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        AtomicBoolean waitPassed = new AtomicBoolean(false);
        queue.submit(new WaitFenceCommand(fence));
        queue.submit(() -> waitPassed.set(true));
        queue.swapFrames();
        // 上一帧（悬挂帧）必须正常完成：若渲染线程阻塞等 fence，这里会死锁
        queue.swapFramesAndSync();
        assertFalse(waitPassed.get(), "fence 未 signal 时悬挂点后的命令不得执行");
        // 主线程被释放推进后（模拟 BoxUtil 过 Phaser）信号到来，续跑任务会合
        fence.signal();
        long deadline = System.currentTimeMillis() + 5000;
        while (!waitPassed.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(waitPassed.get(), "fence signal 后续跑任务必须执行完余下命令");
    }

    /**
     * 悬挂不阻塞后续帧：悬挂帧的续跑任务在队列中自旋等待时，之后提交的帧
     * 必须照常执行完成（续跑任务 requeue 到队尾，不霸占渲染线程）。
     */
    @Test
    void suspendedFrameDoesNotBlockSubsequentFrames() {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        queue.submit(new WaitFenceCommand(fence));
        queue.swapFrames();
        AtomicBoolean laterFrameRan = new AtomicBoolean(false);
        queue.submit(() -> laterFrameRan.set(true));
        queue.swapFrames();
        // 等待的是「后一帧」的前一帧；再 swap 一次确保后一帧本身的完成被等到
        queue.swapFramesAndSync();
        queue.swapFramesAndSync();
        assertTrue(laterFrameRan.get(), "悬挂帧不得阻塞后续帧的执行");
        assertFalse(fence.isSignaled());
    }

    /**
     * 帧执行失败的兜底：帧内登记的 fence（glFenceSync 注册）必须被强制 signal——
     * 否则信号命令随失败帧被丢弃，等待它的悬挂续跑任务永久自旋。
     */
    @Test
    void frameFailureForceSignalsRegisteredFences() {
        queue = new RenderQueueImpl();
        FrameFence fence = new FrameFenceImpl();
        queue.currentFrame().addFence(fence);
        queue.submit(() -> {
            throw new RuntimeException("boom");
        });
        queue.swapFrames();
        // 下一次 swap 等待上一帧完成时收到失败重抛
        assertThrows(IllegalStateException.class, queue::swapFramesAndSync);
        assertTrue(fence.isSignaled(), "失败帧登记的 fence 必须被强制 signal");
    }
}
