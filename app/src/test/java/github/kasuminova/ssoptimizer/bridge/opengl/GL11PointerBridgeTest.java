package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * bridge GL11 的 buffer/pointer 快照语义验证。
 * <p>
 * 命令体是真实 GL 调用，无上下文环境不可执行；这里验证的是录制侧行为——
 * 快照与源 buffer 的隔离、pointer 状态的捕获/替换/引用计数。命令执行后的
 * 归还路径由 {@code RenderQueueImpl} 真实渲染线程测试覆盖（命令体为纯 Java）。
 */
class GL11PointerBridgeTest {

    private FakeRenderQueue queue;

    @BeforeEach
    void setUp() {
        queue = new FakeRenderQueue();
        GL11.install(queue);
        BridgeSupport.resetPoolForTesting();
    }

    @AfterEach
    void tearDown() {
        GL11.uninstall();
    }

    @Test
    void vertexPointerSnapshotsBufferAtRecordTime() {
        FloatBuffer vertices = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        vertices.put(new float[]{1f, 2f, 3f, 4f});
        vertices.flip();

        GL11.glVertexPointer(2, 0, vertices);
        assertEquals(0, queue.recorded.size(), "pointer 设置不产生队列命令");

        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        assertEquals(1, group.size());
        PointerSnapshot snapshot = group.get(0);
        assertEquals(PointerSnapshot.Kind.VERTEX, snapshot.kind);
        assertEquals(2, snapshot.size);
        assertEquals(org.lwjgl.opengl.GL11.GL_FLOAT, snapshot.type);
        assertNotNull(snapshot.data);

        // 录制后调用方改写源 buffer（复用场景），快照不受影响
        vertices.put(0, 99f);
        vertices.put(1, -99f);
        FloatBuffer view = snapshot.data.asFloatBuffer();
        assertEquals(4, view.remaining());
        assertEquals(1f, view.get(0));
        assertEquals(2f, view.get(1));
        assertEquals(3f, view.get(2));
        assertEquals(4f, view.get(3));

        // 引用计数语义：状态持有 1 份 + 本次捕获 1 份
        group.release();
        FloatBuffer spare = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        spare.put(new float[4]);
        spare.flip();
        assertNotSame(snapshot.data, BridgeSupport.pool().snapshot(spare),
                "状态仍持有时快照不得归还池");
        // 替换 pointer：状态释放旧快照，引用归零归还池
        FloatBuffer vertices2 = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        vertices2.put(new float[4]);
        vertices2.flip();
        GL11.glVertexPointer(2, 0, vertices2);
        FloatBuffer spare2 = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        spare2.put(new float[4]);
        spare2.flip();
        assertSame(snapshot.data, BridgeSupport.pool().snapshot(spare2),
                "状态与捕获引用都释放后快照应归还池并被复用");
        // 清理：释放替换后的状态持有（ spare2 的快照借走了一块，无妨）
        BridgeSupport.pointerState().capture().release();
        GL11.glVertexPointer(2, org.lwjgl.opengl.GL11.GL_FLOAT, 0, 0L);
    }

    @Test
    void drawCommandsCaptureCurrentPointerGroup() {
        FloatBuffer vertices = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        vertices.put(new float[]{0f, 0f, 1f, 1f});
        vertices.flip();
        FloatBuffer colors = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        colors.put(new float[]{1f, 1f, 1f, 1f});
        colors.flip();

        GL11.glVertexPointer(2, 0, vertices);
        GL11.glColorPointer(4, 0, colors);
        GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_QUADS, 0, 1);
        assertEquals(1, queue.recorded.size(), "draw 录制为一条携带快照组的命令");

        // 替换 pointer：旧快照的状态引用被释放，新快照成为当前状态
        FloatBuffer vertices2 = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        vertices2.put(new float[]{5f, 6f, 7f, 8f});
        vertices2.flip();
        GL11.glVertexPointer(2, 0, vertices2);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        assertEquals(2, group.size(), "vertex(新) + color(旧) 两份快照");
        FloatBuffer latest = null;
        for (int i = 0; i < group.size(); i++) {
            PointerSnapshot s = group.get(i);
            if (s.kind == PointerSnapshot.Kind.VERTEX) {
                latest = s.data.asFloatBuffer();
            }
        }
        assertNotNull(latest);
        assertEquals(5f, latest.get(0), "状态应持有最新一次 pointer 设置的快照");
        group.release();
    }

    @Test
    void interleavedArraysOverridesDiscretePointersAndViceVersa() {
        FloatBuffer interleaved = ByteBuffer.allocateDirect(8 * Float.BYTES).asFloatBuffer();
        interleaved.put(new float[8]);
        interleaved.flip();
        GL11.glInterleavedArrays(org.lwjgl.opengl.GL11.GL_T2F_V3F, 0, interleaved);
        PointerSnapshotGroup g1 = BridgeSupport.pointerState().capture();
        assertEquals(1, g1.size());
        assertEquals(PointerSnapshot.Kind.INTERLEAVED, g1.get(0).kind);
        assertEquals(org.lwjgl.opengl.GL11.GL_T2F_V3F, g1.get(0).size, "interleaved 的 format 存于 size 字段");
        g1.release();

        FloatBuffer vertices = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        vertices.put(new float[4]);
        vertices.flip();
        GL11.glVertexPointer(2, 0, vertices);
        PointerSnapshotGroup g2 = BridgeSupport.pointerState().capture();
        assertEquals(1, g2.size(), "离散 pointer 设置后 interleaved 失效");
        assertEquals(PointerSnapshot.Kind.VERTEX, g2.get(0).kind);
        g2.release();
    }

    @Test
    void vboOffsetPointersAreRecordedWithoutSnapshot() {
        GL11.glVertexPointer(2, org.lwjgl.opengl.GL11.GL_FLOAT, 0, 16L);
        GL11.glDrawArrays(org.lwjgl.opengl.GL11.GL_QUADS, 0, 4);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        assertEquals(1, group.size());
        PointerSnapshot snapshot = group.get(0);
        assertNull(snapshot.data, "VBO 偏移形式无 buffer 快照");
        assertEquals(16L, snapshot.offset);
        group.release();
        assertEquals(1, queue.recorded.size());
    }

    @Test
    void vboOffsetPointerCapturesArrayBufferBindingAtRecordTime() {
        // LazyFont 序列：绑定 VBO → 设 offset pointer → 解绑 → draw；
        // 快照必须携带 pointer 调用时刻的绑定，供重放时恢复（见 PointerSnapshotGroup.apply）
        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 42);
        GL11.glVertexPointer(2, org.lwjgl.opengl.GL11.GL_FLOAT, 0, 16L);
        PointerSnapshotGroup group = BridgeSupport.pointerState().capture();
        assertEquals(42, group.get(0).vboId, "offset 指针必须快照录制时刻的 ARRAY_BUFFER 绑定");
        group.release();

        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0);
        GL11.glVertexPointer(2, org.lwjgl.opengl.GL11.GL_FLOAT, 0, 32L);
        PointerSnapshotGroup g2 = BridgeSupport.pointerState().capture();
        assertEquals(0, g2.get(0).vboId, "解绑后设置的指针捕获新绑定 0");
        g2.release();
    }

    @Test
    void drawElementsSnapshotsIndicesAtRecordTime() {
        java.nio.IntBuffer indices = java.nio.ByteBuffer.allocateDirect(3 * Integer.BYTES).asIntBuffer();
        indices.put(new int[]{0, 1, 2});
        indices.flip();
        GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, indices);
        assertEquals(1, queue.recorded.size());

        GL11.glDrawElements(org.lwjgl.opengl.GL11.GL_TRIANGLES, 3, org.lwjgl.opengl.GL11.GL_UNSIGNED_INT, 0L);
        assertEquals(2, queue.recorded.size());

        GL11.glArrayElement(1);
        assertEquals(3, queue.recorded.size());
    }
}
