package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenderThreadDispatch} 的渲染线程同步派发语义验证：
 * <ul>
 *   <li>非渲染线程调用：drain-first（先把当前录制帧提交进渲染线程）且走不计入
 *   StallDetector 的阻塞通道（保存回放等有界非稳态场景豁免熔断，见类 javadoc）；</li>
 *   <li>任务体在渲染线程上执行；渲染线程上再次派发时直接执行（不自死锁）。</li>
 * </ul>
 */
class RenderThreadDispatchTest {

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    @Test
    void runBlockingDrainsFirstAndUsesUncountedChannel() {
        FakeRenderQueue queue = new FakeRenderQueue();
        GL11.install(queue);

        Runnable task = () -> {
        };
        RenderThreadDispatch.runBlocking(task);

        assertEquals(1, queue.swapCount, "派发前必须先提交当前录制帧（drain-first）");
        assertTrue(queue.uncountedBlockingTasks.contains(task),
                "派发必须走不计数阻塞通道（有界非稳态场景豁免 StallDetector 熔断）");
        assertTrue(queue.blockingTasks.isEmpty(), "派发不得走计入 StallDetector 的 wait 通道");
    }

    @Test
    void runBlockingExecutesOnRenderThread() {
        RenderQueueImpl queue = new RenderQueueImpl();
        GL11.install(queue);
        try {
            AtomicReference<String> taskThread = new AtomicReference<>();
            AtomicReference<String> nestedThread = new AtomicReference<>();

            RenderThreadDispatch.runBlocking(() -> {
                taskThread.set(Thread.currentThread().getName());
                // 渲染线程上再次派发：必须直接执行而不是走提交队列（否则自死锁）
                RenderThreadDispatch.runBlocking(() -> nestedThread.set(Thread.currentThread().getName()));
            });

            assertEquals(RenderQueueImpl.RENDER_THREAD_NAME, taskThread.get(),
                    "任务体必须在渲染线程上执行");
            assertEquals(RenderQueueImpl.RENDER_THREAD_NAME, nestedThread.get(),
                    "渲染线程上的嵌套派发必须直接执行");
        } finally {
            queue.shutdown();
        }
    }
}
