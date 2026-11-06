package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratedBridgeBehaviorTest {

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

    private static IntBuffer ints(int n) {
        return ByteBuffer.allocateDirect(n * Integer.BYTES).asIntBuffer();
    }

    @Test
    void commandTypeIsRecorded() {
        ARBCopyBuffer.glCopyBufferSubData(0, 0, 0L, 0L, 4L);
        EXTBlendColor.glBlendColorEXT(1.0f, 0.5f, 0.25f, 1.0f);
        assertEquals(2, queue.recorded.size(), "两条命令型调用都必须各录一条命令");
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void snapshotCommandTypeIsRecorded() {
        ARBTransposeMatrix.glLoadTransposeMatrixARB(
                ByteBuffer.allocateDirect(16 * Float.BYTES).asFloatBuffer());
        assertEquals(1, queue.recorded.size());
        assertEquals(0, queue.blockingTasks.size());
    }

    @Test
    void genResourceUsesUncountedChannel() {
        NVFence.glGenFencesNV(ints(4));
        assertEquals(1, queue.uncountedBlockingTasks.size());
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void getFillUsesBlockingWait() {
        NVFence.glGetFenceivNV(1, 0, ints(4));
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void valueGetUsesBlockingGet() {
        queue.getHandler = callable -> true;
        boolean result = NVFence.glIsFenceNV(1);
        assertEquals(true, result);
        assertEquals(1, queue.getCallCount);
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void multiBufferUsesPassthroughWait() {
        ARBImaging.glSeparableFilter2D(0, 0, 1, 1, 0, 0,
                ByteBuffer.allocateDirect(4), ByteBuffer.allocateDirect(4));
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }
}
