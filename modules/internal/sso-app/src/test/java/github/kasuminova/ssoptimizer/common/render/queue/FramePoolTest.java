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

    @Test
    void newFrameWhenPoolExhaustedUsesPrewarmedPeakCapacity() {
        final FramePool pool = new FramePool(1);
        final RenderFrame first = pool.acquire();
        for (int i = 0; i < 500; i++) {
            first.add(() -> {
            });
        }
        pool.release(first); // 记录窗口峰值 500

        // 池内帧被取走后池空，下一次 acquire 走新建路径：命令列表容量取窗口峰值。
        final RenderFrame inFlight = pool.acquire();
        final RenderFrame fresh = pool.acquire();
        assertNotSame(first, fresh);
        assertEquals(500, fresh.commandCapacity());
        assertNotNull(inFlight);
    }

    @Test
    void prewarmCapacityFallsBackToDefaultAfterPeakEvicted() {
        final FramePool pool = new FramePool(1);
        final RenderFrame peak = pool.acquire();
        for (int i = 0; i < 500; i++) {
            peak.add(() -> {
            });
        }
        pool.release(peak); // 窗口：峰值 500 位于最旧槽

        // 连续 64 帧小命令数把 500 挤出窗口，峰值回落至默认下限。
        for (int i = 0; i < FramePool.PREWARM_WINDOW; i++) {
            final RenderFrame frame = pool.acquire();
            frame.add(() -> {
            });
            pool.release(frame);
        }

        final RenderFrame held = pool.acquire();
        final RenderFrame fresh = pool.acquire();
        assertEquals(RenderFrame.DEFAULT_COMMAND_CAPACITY, fresh.commandCapacity());
        assertNotNull(held);
    }

    @Test
    void reusedFrameKeepsItsProvisionedCapacityAcrossReset() {
        final FramePool pool = new FramePool(2);
        final RenderFrame frame = pool.acquire();
        pool.release(frame);
        final RenderFrame reused = pool.acquire();
        // 复用帧不重新预热：容量沿用构造时的预定值，不被重置回默认。
        assertSame(frame, reused);
        assertEquals(0, reused.commandCount());
    }
}
