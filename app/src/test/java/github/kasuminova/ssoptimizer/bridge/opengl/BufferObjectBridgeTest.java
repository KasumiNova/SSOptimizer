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
        assertEquals(1, BridgeSupport.pointerState().arrayBufferBinding(),
                "ARRAY_BUFFER 绑定需同步录制侧跟踪（offset 指针重放恢复用）");
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
    void gl15GenBuffersServedFromRecordingSideStash() {
        int batchSize = BridgeSupport.BUFFER_ID_STASH_BATCH;
        int[] batch = new int[batchSize];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 100 + i;
        }
        queue.getHandler = callable -> batch;
        assertEquals(100, GL15.glGenBuffers(), "池空时一次阻塞批量补货，返回批次首个 id");
        assertEquals(1, queue.uncountedGetCallCount);
        assertEquals(101, GL15.glGenBuffers());
        assertEquals(102, GL15.glGenBuffers());
        assertEquals(1, queue.uncountedGetCallCount, "stash 命中零阻塞");
        IntBuffer out = ByteBuffer.allocateDirect(4).asIntBuffer();
        GL15.glGenBuffers(out);
        assertEquals(1, queue.uncountedBlockingTasks.size(), "IntBuffer 批量变体仍走不计数阻塞 wait 通道");
        assertEquals(0, queue.getCallCount, "资源申请类不得触碰计数通道");
        assertEquals(0, queue.blockingTasks.size());
    }

    @Test
    void gl15GenBuffersStashRefillsWhenExhausted() {
        int batchSize = BridgeSupport.BUFFER_ID_STASH_BATCH;
        int[] first = new int[batchSize];
        int[] second = new int[batchSize];
        for (int i = 0; i < batchSize; i++) {
            first[i] = 100 + i;
            second[i] = 200 + i;
        }
        int[] refills = {0};
        queue.getHandler = callable -> refills[0]++ == 0 ? first : second;
        for (int i = 0; i < batchSize; i++) {
            assertEquals(100 + i, GL15.glGenBuffers());
        }
        assertEquals(1, queue.uncountedGetCallCount);
        assertEquals(200, GL15.glGenBuffers(), "第 " + (batchSize + 1) + " 次触发第二次批量补货");
        assertEquals(2, queue.uncountedGetCallCount);
    }

    @Test
    void arbVertexBufferObjectMirrorsGl15Semantics() {
        ByteBuffer data = ByteBuffer.allocateDirect(16);
        int[] batch = new int[BridgeSupport.BUFFER_ID_STASH_BATCH];
        for (int i = 0; i < batch.length; i++) {
            batch[i] = 5 + i;
        }
        queue.getHandler = callable -> batch;

        assertEquals(5, ARBVertexBufferObject.glGenBuffersARB(), "ARB 与 GL15 共享 stash：补货后返回批次首个 id");
        assertEquals(1, queue.uncountedGetCallCount);
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
