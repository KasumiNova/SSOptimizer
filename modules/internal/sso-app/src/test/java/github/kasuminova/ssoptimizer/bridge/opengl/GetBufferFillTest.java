package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class GetBufferFillTest {

    private RenderQueueImpl queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    @Test
    void smallBufferIsFilledViaStagingAndCopiedBack() {
        IntBuffer params = IntBuffer.allocate(4);
        GetBufferFill.fillInts(params, 16, buf -> {
            for (int i = 0; i < 4; i++) {
                buf.put(buf.position() + i, 100 + i);
            }
        });
        assertEquals(0, params.position(), "填充不得改变调用方 position");
        for (int i = 0; i < 4; i++) {
            assertEquals(100 + i, params.get(i));
        }
    }

    @Test
    void copyBackRespectsCallTimePosition() {
        IntBuffer params = IntBuffer.allocate(8);
        params.put(0, -1).put(1, -1);
        params.position(2);
        GetBufferFill.fillInts(params, 16, buf -> {
            for (int i = 0; i < 6; i++) {
                buf.put(i, 200 + i);
            }
        });
        assertEquals(2, params.position(), "position 语义保持（LWJGL 不推进 position）");
        assertEquals(-1, params.get(0));
        assertEquals(-1, params.get(1));
        for (int i = 0; i < 6; i++) {
            assertEquals(200 + i, params.get(2 + i), "写回起点必须是调用时 position");
        }
    }

    @Test
    void largeBufferPassesThroughDirectly() {
        IntBuffer params = IntBuffer.allocate(16);
        AtomicReference<IntBuffer> seen = new AtomicReference<>();
        GetBufferFill.fillInts(params, 16, seen::set);
        assertSame(params, seen.get(), "remaining ≥ 下限必须原样直通（零开销路径）");
    }

    @Test
    void stagingIsZeroedBetweenCallsSoNoStaleLeak() {
        IntBuffer first = IntBuffer.allocate(4);
        GetBufferFill.fillInts(first, 16, buf -> {
            for (int i = 0; i < 4; i++) {
                buf.put(i, 300 + i);
            }
        });
        IntBuffer second = IntBuffer.allocate(4);
        GetBufferFill.fillInts(second, 16, buf -> buf.put(0, 7));
        assertEquals(7, second.get(0));
        assertEquals(0, second.get(1), "暂存残留不得泄漏进调用方缓冲");
        assertEquals(0, second.get(2));
        assertEquals(0, second.get(3));
    }

    @Test
    void floatAndBooleanVariantsFillSmallBuffers() {
        FloatBuffer floats = FloatBuffer.allocate(4);
        GetBufferFill.fillFloats(floats, 16, buf -> {
            for (int i = 0; i < 4; i++) {
                buf.put(i, 1.5f + i);
            }
        });
        for (int i = 0; i < 4; i++) {
            assertEquals(1.5f + i, floats.get(i));
        }

        ByteBuffer booleans = ByteBuffer.allocate(4);
        GetBufferFill.fillBooleans(booleans, 16, buf -> {
            for (int i = 0; i < 4; i++) {
                buf.put(i, (byte) (i % 2));
            }
        });
        for (int i = 0; i < 4; i++) {
            assertEquals((byte) (i % 2), booleans.get(i));
        }
    }

    @Test
    void fillWorksThroughRealRenderThreadBlockingChannel() {
        queue = new RenderQueueImpl();
        IntBuffer params = IntBuffer.allocate(4);
        queue.wait(() -> GetBufferFill.fillInts(params, 16, buf -> {
            for (int i = 0; i < 4; i++) {
                buf.put(i, 400 + i);
            }
        }));
        for (int i = 0; i < 4; i++) {
            assertEquals(400 + i, params.get(i), "渲染线程填充结果必须回传到调用方缓冲");
        }
    }
}
