package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GlDispatch} 门面的录制行为验证：submit 顺序入队、allocate 走资源申请
 * （不计数）阻塞通道、isRenderThread 空安全、上下文重建监听器逐个通知且
 * 单点异常不中断其余监听器。
 */
class GlDispatchTest {

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    @Test
    void submitEnqueuesInOrder() {
        final FakeRenderQueue queue = new FakeRenderQueue();
        GL11.install(queue);

        final List<String> executed = new ArrayList<>();
        GlDispatch.submit(() -> executed.add("a"));
        GlDispatch.submit(() -> executed.add("b"));

        assertEquals(2, queue.recorded.size());
        queue.recorded.forEach(command -> command.execute());
        assertEquals(List.of("a", "b"), executed, "按提交顺序执行");
    }

    @Test
    void allocateUsesUncountedResourceChannel() {
        final FakeRenderQueue queue = new FakeRenderQueue();
        queue.getHandler = callable -> 42;
        GL11.install(queue);

        final Integer textureId = GlDispatch.allocate(() -> 42);

        assertEquals(42, textureId);
        assertEquals(1, queue.uncountedGetCallCount, "资源申请不计入 StallDetector 通道");
        assertEquals(0, queue.getCallCount);
        assertEquals(1, queue.swapCount, "非渲染线程先提交当前帧再取值（顺序语义）");
    }

    @Test
    void isRenderThreadIsSafeWithoutInstalledQueue() {
        GL11.uninstall();
        assertFalse(GlDispatch.isRenderThread(), "队列未安装时返回 false 而非抛错");
    }

    @Test
    void contextRecreatedNotifiesAllListenersDespiteFailures() {
        GL11.install(new FakeRenderQueue());
        final AtomicInteger first = new AtomicInteger();
        final AtomicInteger third = new AtomicInteger();
        GlDispatch.registerContextRecreatedListener(first::incrementAndGet);
        GlDispatch.registerContextRecreatedListener(() -> {
            throw new IllegalStateException("模拟监听器故障");
        });
        GlDispatch.registerContextRecreatedListener(third::incrementAndGet);

        BridgeSupport.onContextRecreated();

        assertEquals(1, first.get());
        assertEquals(1, third.get(), "单个监听器抛错不中断其余监听器");
    }
}
