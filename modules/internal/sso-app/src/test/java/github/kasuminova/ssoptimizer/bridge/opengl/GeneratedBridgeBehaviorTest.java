package github.kasuminova.ssoptimizer.bridge.opengl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 全量镜像生成物（tools/gen_gl_bridge.py 产物）的通道行为抽测。
 * <p>
 * 生成体只经 BridgeSupport 的四条通道，与手写 bridge 同构：命令型入队录制、
 * 快照命令型录制时刻深拷贝、缓冲填充 getter 与阻塞直通走阻塞 wait 通道、
 * glGen 族走资源申请通道（不计 StallDetector）、取值型走阻塞 get 通道。
 * 抽测纯生成类各一条代表路径（惯例同 {@link BridgeGapFillTest}）。
 */
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
        // 命令型：ARBCopyBuffer（标量参数）与 EXTBlendColor（float 参数）入队录制
        ARBCopyBuffer.glCopyBufferSubData(0, 0, 0L, 0L, 4L);
        EXTBlendColor.glBlendColorEXT(1.0f, 0.5f, 0.25f, 1.0f);
        assertEquals(2, queue.recorded.size(), "两条命令型调用都必须各录一条命令");
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(0, queue.getCallCount);
    }

    @Test
    void snapshotCommandTypeIsRecorded() {
        // 快照命令型：单 FloatBuffer 参数经 enqueueSnapshot 录制（录制时刻深拷贝）
        ARBTransposeMatrix.glLoadTransposeMatrixARB(
                ByteBuffer.allocateDirect(16 * Float.BYTES).asFloatBuffer());
        assertEquals(1, queue.recorded.size());
        assertEquals(0, queue.blockingTasks.size());
    }

    @Test
    void genResourceUsesUncountedChannel() {
        // 阻塞资源型：glGen 族走资源申请通道（不计 StallDetector），直传调用方 buffer
        NVFence.glGenFencesNV(ints(4));
        assertEquals(1, queue.uncountedBlockingTasks.size());
        assertEquals(0, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void getFillUsesBlockingWait() {
        // 缓冲填充 getter：单 IntBuffer 的 glGet 族走阻塞 wait 通道（GetBufferFill）
        NVFence.glGetFenceivNV(1, 0, ints(4));
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void valueGetUsesBlockingGet() {
        // 阻塞取值型：非 void 返回走阻塞 get 通道（桩返回值原样透传）
        queue.getHandler = callable -> true;
        boolean result = NVFence.glIsFenceNV(1);
        assertEquals(true, result);
        assertEquals(1, queue.getCallCount);
        assertEquals(0, queue.recorded.size());
    }

    @Test
    void multiBufferUsesPassthroughWait() {
        // 阻塞直通型：多 buffer 参数走阻塞 wait 通道直传（ARBImaging 卷积滤波双输出）
        ARBImaging.glSeparableFilter2D(0, 0, 1, 1, 0, 0,
                ByteBuffer.allocateDirect(4), ByteBuffer.allocateDirect(4));
        assertEquals(1, queue.blockingTasks.size());
        assertEquals(0, queue.recorded.size());
    }
}
