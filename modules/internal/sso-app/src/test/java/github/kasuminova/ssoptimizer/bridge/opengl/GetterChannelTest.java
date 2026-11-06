package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.StallDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GetterChannelTest {

    private RenderQueueImpl queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
        github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode.resetLoadingFinishedForTesting();
    }

    @Test
    void getExecutesOnRenderThreadAndReturnsResult() {
        queue = new RenderQueueImpl();
        String threadName = queue.get(() -> Thread.currentThread().getName());
        assertEquals(RenderQueueImpl.RENDER_THREAD_NAME, threadName, "getter 必须在渲染线程执行");

        AtomicBoolean wasRenderThread = new AtomicBoolean();
        queue.wait(() -> wasRenderThread.set(queue.isRenderThread()));
        assertTrue(wasRenderThread.get());
        assertFalse(queue.isRenderThread(), "调用方线程不是渲染线程");
    }

    @Test
    void stallsAreCountedIntoStallDetectorAndTripAtThreshold() {
        StallDetector detector = new StallDetector(60, 2);
        queue = new RenderQueueImpl(new github.kasuminova.ssoptimizer.common.render.queue.FramePool(
                github.kasuminova.ssoptimizer.common.render.queue.FramePool.DEFAULT_CAPACITY), detector);
        github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode.markLoadingFinished();
        queue.get(() -> 1);
        assertEquals(1, detector.currentWindowStalls(), "每次阻塞调用计入 StallDetector");
        queue.swapFramesAndSync();
        assertEquals(2, (int) queue.get(() -> 2), "达到阈值的阻塞调用在 warn 策略下仍须正常服务");
        assertEquals(2, detector.currentWindowStalls());
    }

    @Test
    void nestedGetOnRenderThreadExecutesInline() {
        queue = new RenderQueueImpl();
        int result = queue.get(() -> {
            return queue.get(() -> 42);
        });
        assertEquals(42, result);
    }

    @Test
    void renderThreadFailurePropagatesToCaller() {
        queue = new RenderQueueImpl();
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> queue.get(() -> {
                    throw new UnsupportedOperationException("getter 体失败");
                }));
        assertInstanceOf(UnsupportedOperationException.class, thrown.getCause());
    }

    @Test
    void mainThreadIsClaimedByFirstSwapFramesAndSyncCaller() throws Exception {
        queue = new RenderQueueImpl();
        assertTrue(RenderQueueImpl.isMainThread(), "初始主线程为类加载线程（测试线程）");

        Thread loopThread = new Thread(() -> queue.swapFramesAndSync(), "simulated-game-loop");
        loopThread.start();
        loopThread.join(5000);
        assertFalse(loopThread.isAlive(), "swapFramesAndSync 必须正常返回");

        assertFalse(RenderQueueImpl.isMainThread(), "认领迁移后原线程不再是主录制线程");

        queue.swapFramesAndSync();
        assertTrue(RenderQueueImpl.isMainThread(), "再次 swap 后认领回到测试线程");
    }
}
