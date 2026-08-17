package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GL13/GL14/GL15/ARBVertexBufferObject bridge 的录制行为抽查。
 * GL15 与 ARBVertexBufferObject 是同语义别名对，验证各自镜像方法的录制与
 * 阻塞通道路由一致。
 */
class BufferObjectBridgeTest {

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
    void gl13AndGl14CallsAreRecorded() {
        GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE1);
        GL14.glBlendEquation(org.lwjgl.opengl.GL14.GL_FUNC_ADD);
        assertEquals(2, queue.recorded.size());
    }

    @Test
    void gl15BufferCallsAreRecorded() {
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        FloatBuffer floatData = ByteBuffer.allocateDirect(4 * Float.BYTES).asFloatBuffer();
        floatData.put(new float[4]);
        floatData.flip();
        IntBuffer ids = ByteBuffer.allocateDirect(4).asIntBuffer();
        ids.put(1);
        ids.flip();

        GL15.glBindBuffer(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 1);
        GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 1024L, org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, data, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
        GL15.glBufferData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, floatData, org.lwjgl.opengl.GL15.GL_STATIC_DRAW);
        GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 0L, data);
        GL15.glBufferSubData(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER, 8L, floatData);
        GL15.glDeleteBuffers(1);
        GL15.glDeleteBuffers(ids);
        assertEquals(8, queue.recorded.size());
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void gl15GenBuffersRoutesThroughBlockingChannel() {
        queue.getHandler = callable -> 9;
        assertEquals(9, GL15.glGenBuffers());
        IntBuffer out = ByteBuffer.allocateDirect(4).asIntBuffer();
        GL15.glGenBuffers(out);
        assertEquals(1, queue.getCallCount);
        assertEquals(1, queue.blockingTasks.size());
    }

    @Test
    void arbVertexBufferObjectMirrorsGl15Semantics() {
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        queue.getHandler = callable -> 5;

        assertEquals(5, ARBVertexBufferObject.glGenBuffersARB(), "ARB 资源分配同样走阻塞通道");
        ARBVertexBufferObject.glBindBufferARB(org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB, 5);
        ARBVertexBufferObject.glBufferDataARB(org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB,
                1024L, org.lwjgl.opengl.ARBVertexBufferObject.GL_DYNAMIC_DRAW_ARB);
        ARBVertexBufferObject.glBufferDataARB(org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB,
                data, org.lwjgl.opengl.ARBVertexBufferObject.GL_STATIC_DRAW_ARB);
        ARBVertexBufferObject.glBufferSubDataARB(org.lwjgl.opengl.ARBVertexBufferObject.GL_ARRAY_BUFFER_ARB, 0L, data);
        ARBVertexBufferObject.glDeleteBuffersARB(5);
        assertEquals(5, queue.recorded.size());
    }
}
