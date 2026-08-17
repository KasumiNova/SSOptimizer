package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 阻塞式调用熔断器：滑动窗口统计主线程在 swap 之间的阻塞式 GL 调用
 * （{@link RenderQueue#get}/{@link RenderQueue#wait}）次数，超阈值即抛异常。
 * <p>
 * 动机（照搬 FR 的设计意图）：每一次阻塞式调用都是一次全管线 drain，模组若用
 * getter 回读做每帧逻辑（未仿真的 pname），会把双线程管线打回串行甚至造成隐性
 * 死锁。这类问题静默发生时极难排查，因此在窗口内 stall 密度超过阈值时直接以
 * 带诊断信息的异常暴露。默认窗口 {@value #DEFAULT_WINDOW_FRAMES} 帧、阈值
 * {@value #DEFAULT_THRESHOLD} 次（FR 同参数）。
 * <p>
 * 非线程安全：stall/swap 事件都只发生在主线程（get/wait 的渲染线程直执路径不计数）。
 */
public final class StallDetector {
    /** 默认滑动窗口大小（帧）。 */
    public static final int DEFAULT_WINDOW_FRAMES = 60;
    /** 默认窗口内 stall 阈值（达到即抛异常）。 */
    public static final int DEFAULT_THRESHOLD = 30;

    private final int windowFrames;
    private final int threshold;
    /** 环形缓冲：每个槽位记录对应帧内的 stall 次数。 */
    private final int[] stallsPerFrame;
    private long frameIndex;
    private int windowTotal;

    public StallDetector() {
        this(DEFAULT_WINDOW_FRAMES, DEFAULT_THRESHOLD);
    }

    /**
     * @param windowFrames 滑动窗口大小（帧），至少为 1
     * @param threshold    窗口内允许的 stall 上限（达到即抛），至少为 1
     */
    public StallDetector(int windowFrames, int threshold) {
        if (windowFrames < 1 || threshold < 1) {
            throw new IllegalArgumentException(
                    "windowFrames/threshold must be >= 1, got " + windowFrames + "/" + threshold);
        }
        this.windowFrames = windowFrames;
        this.threshold = threshold;
        this.stallsPerFrame = new int[windowFrames];
    }

    /**
     * 记录一次阻塞式调用；窗口内累计达到阈值时抛 {@link IllegalStateException}。
     */
    public void onStall() {
        stallsPerFrame[(int) (frameIndex % windowFrames)]++;
        windowTotal++;
        if (windowTotal >= threshold) {
            throw new IllegalStateException(
                    "[SSOptimizer] 最近 " + windowFrames + " 帧内发生 " + windowTotal
                            + " 次阻塞式 GL 调用（阈值 " + threshold + "）。"
                            + "有模组在主线程做高频 getter 回读/同步调用，正在打穿渲染管线；"
                            + "请为该调用补状态仿真或改为帧级一次性调用，勿静默放行以免演变为隐性死锁。");
        }
    }

    /**
     * 记录一次帧交换：窗口前进一帧，最旧一帧的 stall 计数过期。
     */
    public void onSwap() {
        frameIndex++;
        int slot = (int) (frameIndex % windowFrames);
        windowTotal -= stallsPerFrame[slot];
        stallsPerFrame[slot] = 0;
    }

    /**
     * @return 当前窗口内的 stall 总数（诊断/测试用）
     */
    public int currentWindowStalls() {
        return windowTotal;
    }
}
