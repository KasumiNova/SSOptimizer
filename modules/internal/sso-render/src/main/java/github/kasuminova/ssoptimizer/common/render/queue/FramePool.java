package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.ArrayDeque;

public final class FramePool {
    public static final int DEFAULT_CAPACITY = 4;

    static final int PREWARM_WINDOW = 64;

    private static final Logger LOGGER = Logger.getLogger(FramePool.class);

    private final int capacity;
    private final ArrayDeque<RenderFrame> idle = new ArrayDeque<>();
    private final int[] recentFrameSizes = new int[PREWARM_WINDOW];
    private       int recentIndex;
    /** 滑动窗口峰值仅由 synchronized (idle) 保护读写。 */
    private int prewarmCapacity = RenderFrame.DEFAULT_COMMAND_CAPACITY;

    public FramePool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    public RenderFrame acquire() {
        synchronized (idle) {
            RenderFrame frame = idle.poll();
            if (frame != null) {
                return frame;
            }
            LOGGER.info("[SSOptimizer] FramePool 空闲帧耗尽，新建帧（稳态不应超过双缓冲+在途=3，请检查是否存在超额并发持帧）");
            return new RenderFrame(prewarmCapacity);
        }
    }

    public void release(RenderFrame frame) {
        final int frameSize = frame.commandCount();
        frame.reset();
        synchronized (idle) {
            recordFrameSize(frameSize);
            if (idle.size() >= capacity) {
                LOGGER.info("[SSOptimizer] FramePool 已满，归还的帧被丢弃（峰值瞬态，不扩容）");
                return;
            }
            idle.offer(frame);
        }
    }

    private void recordFrameSize(final int frameSize) {
        final int evicted = recentFrameSizes[recentIndex];
        recentFrameSizes[recentIndex] = frameSize;
        recentIndex = (recentIndex + 1) % PREWARM_WINDOW;
        if (frameSize >= prewarmCapacity) {
            prewarmCapacity = frameSize;
        } else if (evicted == prewarmCapacity) {
            prewarmCapacity = RenderFrame.DEFAULT_COMMAND_CAPACITY;
            for (final int windowSize : recentFrameSizes) {
                if (windowSize > prewarmCapacity) {
                    prewarmCapacity = windowSize;
                }
            }
        }
    }

    public int idleCount() {
        synchronized (idle) {
            return idle.size();
        }
    }
}
