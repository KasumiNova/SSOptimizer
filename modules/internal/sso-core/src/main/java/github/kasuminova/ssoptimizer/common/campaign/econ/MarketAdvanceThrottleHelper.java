package github.kasuminova.ssoptimizer.common.campaign.econ;

import org.apache.log4j.Logger;

/**
 * 合并市场推进请求，按间隔以累计时长执行真实推进。
 * 新市场首次调用立即推进；全部状态读写仅限战役主线程，{@code interval=1} 时逐次转发。
 */
public final class MarketAdvanceThrottleHelper {
    public static final String INTERVAL_PROPERTY = "ssoptimizer.econ.advance.interval";
    public static final int DEFAULT_INTERVAL = 2;

    private static final Logger LOGGER = Logger.getLogger(MarketAdvanceThrottleHelper.class);

    private static final int ADVANCE_INTERVAL = parseInterval(System.getProperty(INTERVAL_PROPERTY));

    private MarketAdvanceThrottleHelper() {
    }

    static int advanceInterval() {
        return ADVANCE_INTERVAL;
    }

    public static void advanceThrottled(final MarketAdvanceThrottleBridge bridge, final float amount) {
        advanceThrottled(bridge, amount, ADVANCE_INTERVAL);
    }

    public static void advanceThrottled(final MarketAdvanceThrottleBridge bridge,
                                        final float amount, final int interval) {
        final double dt = decideAdvanceSeconds(bridge, amount, interval);
        if (!Double.isNaN(dt)) {
            bridge.ssoptimizer$advanceNow((float) dt);
        }
    }

    /**
     * 累计请求时长并返回本次应推进的秒数，跳过时返回 {@link Double#NaN}。
     * 状态无同步保护，仅限 {@code Economy.advance} 所在战役主线程调用。
     */
    public static double decideAdvanceSeconds(final MarketAdvanceThrottleBridge bridge,
                                              final float amount, final int interval) {
        final int calls = bridge.ssoptimizer$getAdvanceCallCount() + 1;
        bridge.ssoptimizer$setAdvanceCallCount(calls);

        if (calls == 1) {
            return amount;
        }

        final double pending = bridge.ssoptimizer$getPendingAdvanceSeconds() + amount;
        if (calls % interval == 0) {
            bridge.ssoptimizer$setPendingAdvanceSeconds(0.0D);
            return pending;
        }
        bridge.ssoptimizer$setPendingAdvanceSeconds(pending);
        return Double.NaN;
    }

    public static int parseInterval(final String raw) {
        if (raw == null) {
            return DEFAULT_INTERVAL;
        }

        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            LOGGER.warn("[SSOptimizer] " + INTERVAL_PROPERTY + " 取值 \"" + raw
                    + "\" 无法解析，按 1（逐帧推进）处理", e);
            return 1;
        }

        if (parsed <= 0) {
            LOGGER.warn("[SSOptimizer] " + INTERVAL_PROPERTY + " 取值 " + parsed
                    + " 非法（必须 ≥1），按 1（逐帧推进）处理");
            return 1;
        }
        return parsed;
    }
}
