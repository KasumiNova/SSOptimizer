package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GL_ARRAY_BUFFER 绑定簿记与溯源机制的完整逻辑验证。
 * <p>
 * 覆盖：生成器 command_bind_buffer 特判的录制侧簿记（pointerState 跟踪 +
 * 命令入队）、执行侧簿记更新与溯源环记录、帧失败后的簿记校验与自愈
 * （{@link BridgeSupport#reconcileArrayBufferBindingAfterFailure()}）。
 */
class ArrayBufferBindTraceTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        BridgeSupport.install(queue);
    }

    @AfterEach
    void tearDown() {
        BridgeSupport.uninstall();
    }

    @Test
    void generatedBindTracksArrayBufferOnRecordSide() {
        // 生成类（ARBBufferObject）的 ARRAY_BUFFER 绑定：录制侧 pointerState 跟踪 +
        // 一条录制命令；非 ARRAY_BUFFER 目标不触碰 pointerState
        ARBBufferObject.glBindBufferARB(
                org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB, 7);
        assertEquals(1, queue.recorded.size(), "绑定必须录制一条命令");
        assertEquals(7, BridgeSupport.pointerState().arrayBufferBinding(),
                "录制侧必须跟踪 ARRAY_BUFFER 绑定（offset 指针重放恢复用）");

        ARBBufferObject.glBindBufferARB(
                org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER, 9);
        assertEquals(2, queue.recorded.size());
        assertEquals(7, BridgeSupport.pointerState().arrayBufferBinding(),
                "非 ARRAY_BUFFER 目标不得改动录制侧 ARRAY_BUFFER 跟踪");
    }

    @Test
    void pixelBufferObjectBindEntriesAreAlsoBooked() {
        // 继承链上的绑定入口（ARBPixelBufferObject/EXTPixelBufferObject 继承
        // ARBBufferObject 的 glBindBufferARB）同样是簿记型
        ARBPixelBufferObject.glBindBufferARB(
                org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB, 5);
        EXTPixelBufferObject.glBindBufferARB(
                org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB, 0);
        assertEquals(2, queue.recorded.size());
        assertEquals(0, BridgeSupport.pointerState().arrayBufferBinding());
    }

    @Test
    void executedBindUpdatesBookkeepingAndTraceRing() {
        assertNull(BridgeSupport.latestBindTraceForTesting(), "初始溯源环为空");

        BridgeSupport.executedArrayBufferBinding(11, "GL15", "main");
        assertEquals(11, BridgeSupport.executedArrayBufferBinding());
        assertEquals("1|11|GL15|main", BridgeSupport.latestBindTraceForTesting(),
                "溯源环必须记录绑定值、入口类与录制线程");

        BridgeSupport.executedArrayBufferBinding(0, "ARBBufferObject", "Thread-3");
        assertEquals(0, BridgeSupport.executedArrayBufferBinding());
        assertEquals("2|0|ARBBufferObject|Thread-3", BridgeSupport.latestBindTraceForTesting());
    }

    @Test
    void reconcileIsSilentWhenBookkeepingMatchesReal() {
        BridgeSupport.executedArrayBufferBinding(11, "GL15", "main");
        BridgeSupport.arrayBufferBindingProbeForTesting(() -> 11);
        assertFalse(BridgeSupport.reconcileArrayBufferBindingAfterFailure(),
                "簿记与真实一致时不得判定失真");
        assertEquals(11, BridgeSupport.executedArrayBufferBinding(), "一致时簿记不得被改写");
    }

    @Test
    void reconcileDetectsDivergenceAndSelfHeals() {
        BridgeSupport.executedArrayBufferBinding(0, "GL15", "main");
        // 模拟「未簿记的绑定来源」：真实绑定 42，簿记 0
        BridgeSupport.arrayBufferBindingProbeForTesting(() -> 42);
        assertTrue(BridgeSupport.reconcileArrayBufferBindingAfterFailure(),
                "簿记与真实不一致必须判定失真");
        assertEquals(42, BridgeSupport.executedArrayBufferBinding(),
                "失真后必须校准簿记=真实值，防止后续帧级联失败");

        // 校准后再次校验：一致，静默
        assertFalse(BridgeSupport.reconcileArrayBufferBindingAfterFailure());
    }

    @Test
    void pushPopClientAttribRestoresArrayBufferOnRecordSide() {
        // GL 1.5+ 起 ARRAY_BUFFER 绑定属 client 状态：push(CLIENT_VERTEX_ARRAY_BIT)
        // 后的 pop 必须恢复录制侧跟踪
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 7);
        GL11.glPushClientAttrib(org.lwjgl.opengl.GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 3);
        GL11.glPopClientAttrib();
        assertEquals(7, BridgeSupport.pointerState().arrayBufferBinding(),
                "pop 必须恢复 push 时刻的录制侧 ARRAY_BUFFER 跟踪");

        // 掩码不含 CLIENT_VERTEX_ARRAY_BIT 的 push/pop 对不触碰绑定
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 5);
        GL11.glPushClientAttrib(0);
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 2);
        GL11.glPopClientAttrib();
        assertEquals(2, BridgeSupport.pointerState().arrayBufferBinding(),
                "掩码不含 CLIENT_VERTEX_ARRAY_BIT 时 pop 不得改动绑定跟踪");

        // 下溢的 pop 是 GL 空操作：簿记保持不变
        GL11.glPopClientAttrib();
        assertEquals(2, BridgeSupport.pointerState().arrayBufferBinding());
    }

    @Test
    void pushPopClientAttribRestoresExecutedBookkeeping() {
        BridgeSupport.executedArrayBufferBinding(7, "GL15", "main");
        BridgeSupport.onExecutedPushClientAttrib(org.lwjgl.opengl.GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
        BridgeSupport.executedArrayBufferBinding(3, "GL15", "main");
        BridgeSupport.onExecutedPopClientAttrib("main");
        assertEquals(7, BridgeSupport.executedArrayBufferBinding(),
                "执行侧簿记必须随 pop 恢复 push 时刻的绑定");
        assertEquals("3|7|GL11.glPopClientAttrib|main", BridgeSupport.latestBindTraceForTesting(),
                "pop 驱动的簿记恢复也必须落溯源环");

        // 下溢空操作
        BridgeSupport.onExecutedPopClientAttrib("main");
        assertEquals(7, BridgeSupport.executedArrayBufferBinding());
    }

    @Test
    void deleteBoundBufferResetsTrackingOnRecordSide() {
        // GL 规范：删除当前绑定的 buffer，其绑定重置为 0——录制侧跟踪同步
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 7);
        GL15.glDeleteBuffers(7);
        assertEquals(0, BridgeSupport.pointerState().arrayBufferBinding(),
                "删除当前绑定的 buffer 必须把录制侧跟踪重置为 0");

        // 批量形式：命中绑定 id 时同样重置，未命中不动
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 8);
        java.nio.IntBuffer ids = java.nio.ByteBuffer.allocateDirect(2 * Integer.BYTES)
                .asIntBuffer().put(new int[]{8, 9});
        ids.flip();
        GL15.glDeleteBuffers(ids);
        assertEquals(0, BridgeSupport.pointerState().arrayBufferBinding());

        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 6);
        GL15.glDeleteBuffers(5);
        assertEquals(6, BridgeSupport.pointerState().arrayBufferBinding(),
                "删除未绑定的 buffer 不得改动绑定跟踪");
    }

    @Test
    void installOnRealQueueRegistersFailureHook() {
        // 真实 RenderQueueImpl 上 install 必须注册帧失败钩子（reconcile 经钩子链路
        // 触发，而非仅可被直接调用）；以 probe 调用计数为观测点
        final github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl realQueue =
                new github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl();
        try {
            BridgeSupport.install(realQueue);
            final java.util.concurrent.atomic.AtomicInteger probeCalls =
                    new java.util.concurrent.atomic.AtomicInteger();
            BridgeSupport.arrayBufferBindingProbeForTesting(probeCalls::incrementAndGet);
            realQueue.submit(() -> {
                throw new IllegalArgumentException("boom");
            });
            realQueue.swapFrames();
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class, realQueue::swapFramesAndSync);
            assertTrue(probeCalls.get() >= 1,
                    "帧失败必须经钩子链路触发簿记校验（真实绑定回读）");
        } finally {
            BridgeSupport.uninstall();
            realQueue.shutdown();
        }
    }
}
