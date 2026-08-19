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

    /** 预热统计窗口（帧 = 每次 {@link #release} 归还的一帧命令数；A3 模式见 bridge/opengl/VertexStream）。 */
    static final int PREWARM_WINDOW = 64;

    private static final Logger LOGGER = Logger.getLogger(FramePool.class);

    private final int capacity;
    private final ArrayDeque<RenderFrame> idle = new ArrayDeque<>();
    /** 近期各帧命令数的环形记录（窗口满后覆盖最旧项）。 */
    private final int[] recentFrameSizes = new int[PREWARM_WINDOW];
    private       int recentIndex;
    /**
     * 近期帧命令数峰值（窗口内最大值）：池空新建帧时作为命令列表初始容量
     * （{@link RenderFrame#commandCapacity()}）。相对历史最大（单调不减，罕见大帧
     * 永久撑大内存需求），窗口峰值在突发帧滑出窗口后回落，新建帧容量与实际命令
     * 量级保持同步（v49 profile：{@code ArrayList.grow} 2,728 样本的消除目标）。
     * 仅受 {@code synchronized (idle)} 保护读写。
     */
    private int prewarmCapacity = RenderFrame.DEFAULT_COMMAND_CAPACITY;

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
     * 取一个可录制的帧。池空时新建并记日志；新建帧的命令列表初始容量取
     * 近 {@value #PREWARM_WINDOW} 帧命令数峰值（{@link #prewarmCapacity}），
     * 池化复用帧则保留各自的峰值容量（reset 的 clear 不缩容）。
     *
     * @return 已 {@link RenderFrame#reset()} 的干净帧
     */
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

    /**
     * 归还帧：记录本帧命令数后重置并放回池中；池满则丢弃。
     *
     * @param frame 渲染线程执行完毕的帧
     */
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

    /**
     * 记录本帧命令数并维护预热容量（窗口峰值；峰值被滑出窗口时重算，
     * 同 {@code VertexStream#recordBatchLength}）。仅受 {@code synchronized (idle)} 保护。
     */
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

    /**
     * @return 当前池内空闲帧数（诊断/测试用）
     */
    public int idleCount() {
        synchronized (idle) {
            return idle.size();
        }
    }
}
