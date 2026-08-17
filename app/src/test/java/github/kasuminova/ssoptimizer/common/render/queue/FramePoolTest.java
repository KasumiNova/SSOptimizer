package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FramePoolTest {

    @Test
    void releasedFrameIsReusedAndReset() {
        FramePool pool = new FramePool(2);
        RenderFrame frame = pool.acquire();
        frame.add(() -> {
        });
        FrameFence fence = new FrameFenceImpl();
        frame.addFence(fence);
        pool.release(frame);

        RenderFrame reused = pool.acquire();
        assertSame(frame, reused);
        assertEquals(0, reused.commandCount());
        assertTrue(reused.fences().isEmpty());
    }

    @Test
    void exhaustedPoolCreatesNewFrame() {
        FramePool pool = new FramePool(1);
        RenderFrame first = pool.acquire();
        assertEquals(0, pool.idleCount());
        // 池空：新建而不是阻塞
        RenderFrame second = pool.acquire();
        assertNotSame(first, second);
    }

    @Test
    void releaseBeyondCapacityDropsFrame() {
        FramePool pool = new FramePool(1);
        RenderFrame first = pool.acquire();
        RenderFrame second = pool.acquire();
        pool.release(first);
        assertEquals(1, pool.idleCount());
        // 池已满：归还的帧被丢弃，不扩容
        pool.release(second);
        assertEquals(1, pool.idleCount());
        assertSame(first, pool.acquire());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new FramePool(0));
    }
}
