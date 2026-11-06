package github.kasuminova.ssoptimizer.common.render.queue;

/** onStall 可由多个录制线程调用，onSwap 仅由主线程推进帧边界。 */
public final class StallDetector {
    public static final int DEFAULT_WINDOW_FRAMES = 60;
    public static final int DEFAULT_THRESHOLD = 30;

    private final int windowFrames;
    private final int threshold;
    private final int[] stallsPerFrame;
    private long frameIndex;
    private int windowTotal;
    private int windowStallFrames;

    public StallDetector() {
        this(DEFAULT_WINDOW_FRAMES, DEFAULT_THRESHOLD);
    }

    public StallDetector(int windowFrames, int threshold) {
        if (windowFrames < 1 || threshold < 1) {
            throw new IllegalArgumentException(
                    "windowFrames/threshold must be >= 1, got " + windowFrames + "/" + threshold);
        }
        this.windowFrames = windowFrames;
        this.threshold = threshold;
        this.stallsPerFrame = new int[windowFrames];
    }

    public synchronized boolean onStall() {
        int slot = (int) (frameIndex % windowFrames);
        if (stallsPerFrame[slot] == 0) {
            windowStallFrames++;
        }
        stallsPerFrame[slot]++;
        windowTotal++;
        return windowStallFrames >= threshold;
    }

    public synchronized String tripDiagnostics() {
        return "最近 " + windowFrames + " 帧内有 " + windowStallFrames
                + " 帧发生阻塞式 GL 调用（阈值 " + threshold + " 帧，窗口内共 " + windowTotal + " 次）。"
                + "有模组在主线程持续做 getter 回读/同步调用，正在打穿渲染管线；"
                + "请为该调用补状态仿真或改为帧级一次性调用，勿静默放行以免演变为隐性死锁。";
    }

    public synchronized void onSwap() {
        frameIndex++;
        int slot = (int) (frameIndex % windowFrames);
        if (stallsPerFrame[slot] > 0) {
            windowStallFrames--;
        }
        windowTotal -= stallsPerFrame[slot];
        stallsPerFrame[slot] = 0;
    }

    public int currentWindowStalls() {
        return windowTotal;
    }
}
