package github.kasuminova.ssoptimizer.common.save;

import org.apache.log4j.Logger;

import java.util.Locale;

/**
 * campaign.xml unmarshal 阶段的毫秒级计时器。
 * <p>
 * 动机：读档总耗时（{@code loadGame} 全程）包含模组 {@code onGameLoad} 后处理等
 * 非序列化工作，评估 XStream 优化效果需要排除这些噪音。该计时器以
 * {@code SaveProgressInputStream} 的生命周期标记 unmarshal 窗口：
 * 首次 {@code updateProgress(true)}（流打开、fromXML 即将开始）到
 * {@code markComplete()}（fromXML 返回）之间即为纯 unmarshal 耗时。<br>
 * 每次读档输出一条 INFO 日志，并由自动化驱动采集进遥测。
 */
public final class UnmarshalPhaseTimer {
    private static final Logger LOGGER = Logger.getLogger(UnmarshalPhaseTimer.class);

    private static volatile long beginNanos = -1;
    private static volatile long lastUnmarshalMs = -1;

    private UnmarshalPhaseTimer() {
    }

    /**
     * 标记 unmarshal 窗口开始（幂等，仅首次生效）。
     */
    public static void begin() {
        if (beginNanos < 0) {
            beginNanos = System.nanoTime();
        }
    }

    /**
     * 标记 unmarshal 窗口结束并输出耗时日志。
     */
    public static void end() {
        final long begin = beginNanos;
        if (begin < 0) {
            return;
        }
        beginNanos = -1;
        lastUnmarshalMs = (System.nanoTime() - begin) / 1_000_000;
        LOGGER.info(String.format(Locale.ROOT, "[SSO-Save] campaign.xml unmarshal: %d ms", lastUnmarshalMs));
    }

    /**
     * 最近一次 unmarshal 耗时（毫秒），未发生过读档时为 -1。
     */
    public static long lastUnmarshalMs() {
        return lastUnmarshalMs;
    }
}
