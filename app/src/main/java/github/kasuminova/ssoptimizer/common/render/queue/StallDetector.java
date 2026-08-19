package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 阻塞式调用熔断器：滑动窗口统计主线程在 swap 之间发生阻塞式 GL 调用
 * （{@link RenderQueue#get}/{@link RenderQueue#wait}）的<b>帧密度</b>，超阈值即抛异常。
 * <p>
 * 动机（照搬 FR 的设计意图）：每一次阻塞式调用都是一次全管线 drain，模组若用
 * getter 回读做每帧逻辑（未仿真的 pname），会把双线程管线打回串行甚至造成隐性
 * 死锁。这类问题静默发生时极难排查，因此在窗口内 stall 密度超过阈值时直接以
 * 带诊断信息的异常暴露。默认窗口 {@value #DEFAULT_WINDOW_FRAMES} 帧、阈值
 * {@value #DEFAULT_THRESHOLD} 帧（FR 同参数）。
 * <p>
 * 计数单位为「stall 帧」而非调用次数：一帧内无论发生多少次阻塞调用（战斗初始化时
 * 各模组的 shader 编译状态轮询等成批一次性回读）只记为 1 个 stall 帧；只有逐帧
 * 持续回读（真正的管线击穿模式）才会在窗口内累积到阈值。{@link #currentWindowStalls()}
 * 仍返回窗口内调用总数，供诊断区分突发与持续两种形态。
 * <p>
 * 非线程安全说明：stall 事件可能由主线程与 aux-context 生产者线程（BoxUtil 等）
 * 并发触发——任一录制线程的阻塞调用都是一次管线 drain，故 {@link #onStall()}
 * 与 {@link #onSwap()} 内部同步；帧边界仍由主线程的 swap 唯一推进。
 * <p>
 * 分类语义：仅回读类阻塞调用（{@link RenderQueue#get}）计入本熔断器；资源申请类
 * 调用（{@link RenderQueue#getUncounted}，glGenBuffers/glCreateProgram 等有界
 * 一次性分配与名称查找）由队列侧绕过，任何时期都不计数。
 */
public final class StallDetector {
    /** 默认滑动窗口大小（帧）。 */
    public static final int DEFAULT_WINDOW_FRAMES = 60;
    /** 默认窗口内 stall 帧阈值（达到即抛异常）。 */
    public static final int DEFAULT_THRESHOLD = 30;

    private final int windowFrames;
    private final int threshold;
    /** 环形缓冲：每个槽位记录对应帧内的 stall 次数。 */
    private final int[] stallsPerFrame;
    private long frameIndex;
    /** 窗口内 stall 调用总数（诊断用）。 */
    private int windowTotal;
    /** 窗口内发生过 stall 的帧数（熔断依据）。 */
    private int windowStallFrames;

    public StallDetector() {
        this(DEFAULT_WINDOW_FRAMES, DEFAULT_THRESHOLD);
    }

    /**
     * @param windowFrames 滑动窗口大小（帧），至少为 1
     * @param threshold    窗口内允许的 stall 帧上限（达到即抛），至少为 1
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
     * 记录一次阻塞式调用；当前帧是窗口内首个 stall 时将其计为一个 stall 帧，
     * 窗口内 stall 帧数达到阈值时抛 {@link IllegalStateException}。
     */
    public synchronized void onStall() {
        int slot = (int) (frameIndex % windowFrames);
        if (stallsPerFrame[slot] == 0) {
            windowStallFrames++;
        }
        stallsPerFrame[slot]++;
        windowTotal++;
        if (windowStallFrames >= threshold) {
            throw new IllegalStateException(
                    "[SSOptimizer] 最近 " + windowFrames + " 帧内有 " + windowStallFrames
                            + " 帧发生阻塞式 GL 调用（阈值 " + threshold + " 帧，窗口内共 " + windowTotal + " 次）。"
                            + "有模组在主线程持续做 getter 回读/同步调用，正在打穿渲染管线；"
                            + "请为该调用补状态仿真或改为帧级一次性调用，勿静默放行以免演变为隐性死锁。");
        }
    }

    /**
     * 记录一次帧交换：窗口前进一帧，最旧一帧的 stall 计数过期。
     */
    public synchronized void onSwap() {
        frameIndex++;
        int slot = (int) (frameIndex % windowFrames);
        if (stallsPerFrame[slot] > 0) {
            windowStallFrames--;
        }
        windowTotal -= stallsPerFrame[slot];
        stallsPerFrame[slot] = 0;
    }

    /**
     * @return 当前窗口内的 stall 调用总数（诊断/测试用；与熔断依据的 stall 帧数区分）
     */
    public int currentWindowStalls() {
        return windowTotal;
    }
}
