package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.ArrayDeque;

/**
 * 帧对象池：复用 {@link RenderFrame} 及其内部命令列表，避免每帧分配。
 * <p>
 * 容量有限（默认 {@value #DEFAULT_CAPACITY}）：正常稳态在途帧不应超过
 * 「双缓冲 + 正在渲染 = 3」，耗尽时新建并记日志提示上层存在超额并发持帧。
 * 归还时若池已满则直接丢弃给 GC——峰值持帧是瞬态，不为它扩容池。
 */
public final class FramePool {
    /** 默认池容量：双缓冲 2 + 在途 1 + 1 冗余。 */
    public static final int DEFAULT_CAPACITY = 4;

    private static final Logger LOGGER = Logger.getLogger(FramePool.class);

    private final int capacity;
    private final ArrayDeque<RenderFrame> idle = new ArrayDeque<>();

    /**
     * @param capacity 池内最多保留的空闲帧数，至少为 1
     */
    public FramePool(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        this.capacity = capacity;
    }

    /**
     * 取一个可录制的帧。池空时新建并记日志。
     *
     * @return 已 {@link RenderFrame#reset()} 的干净帧
     */
    public RenderFrame acquire() {
        synchronized (idle) {
            RenderFrame frame = idle.poll();
            if (frame != null) {
                return frame;
            }
        }
        LOGGER.info("[SSOptimizer] FramePool 空闲帧耗尽，新建帧（稳态不应超过双缓冲+在途=3，请检查是否存在超额并发持帧）");
        return new RenderFrame();
    }

    /**
     * 归还帧：重置后放回池中；池满则丢弃。
     *
     * @param frame 渲染线程执行完毕的帧
     */
    public void release(RenderFrame frame) {
        frame.reset();
        synchronized (idle) {
            if (idle.size() >= capacity) {
                LOGGER.info("[SSOptimizer] FramePool 已满，归还的帧被丢弃（峰值瞬态，不扩容）");
                return;
            }
            idle.offer(frame);
        }
    }

    /**
     * @return 当前池内空闲帧数（诊断/测试用）
     */
    public int idleCount() {
        synchronized (idle) {
            return idle.size();
        }
    }
}
