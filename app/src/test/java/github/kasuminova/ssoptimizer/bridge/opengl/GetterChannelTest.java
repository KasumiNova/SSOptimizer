package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.queue.StallDetector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge getter 依赖的阻塞通道端到端验证：用真实 {@link RenderQueueImpl}
 * （起真实渲染线程），但命令体是纯 Java 断言/取值，不触碰真实 GL。
 * <p>
 * 验证点：getter 在渲染线程执行并回传结果；每次阻塞调用计入 StallDetector，
 * 超阈值熔断；渲染线程内的嵌套 get 直接执行不自死锁。
 */
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
        // 熔断只针对资源加载期结束后的稳态；加载期调用豁免，见 RenderQueueImpl 门控
        github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode.markLoadingFinished();
        queue.get(() -> 1);
        assertEquals(1, detector.currentWindowStalls(), "每次阻塞调用计入 StallDetector");
        assertThrows(IllegalStateException.class, () -> queue.get(() -> 2),
                "达到阈值的阻塞调用必须被熔断");
    }

    @Test
    void nestedGetOnRenderThreadExecutesInline() {
        queue = new RenderQueueImpl();
        int result = queue.get(() -> {
            // 渲染线程内再次 get：直接执行（绕过提交队列防自死锁），且不计 stall
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
}
