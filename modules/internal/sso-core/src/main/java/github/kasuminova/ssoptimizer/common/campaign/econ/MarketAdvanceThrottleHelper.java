package github.kasuminova.ssoptimizer.common.campaign.econ;

import org.apache.log4j.Logger;

/**
 * 市场推进（{@code Market.advance}）降频 helper。
 * <p>
 * 职责：把 {@code Economy.advance} 市场循环中逐帧的 {@code market.advance(amount)}
 * 合并为每 N 帧一次、以累计 dt 转发的真实推进，压低 {@code Market.advance} 热点。<br>
 * 语义依据：原版各累加器（daysInExistence / power / memory / buildProgress / 商品统计）
 * 全部线性，一次 {@code advance(n·dt)} 与 n 次 {@code advance(dt)} 在游戏日内语义等价；
 * 构建完成 / 条件移除等事件的 wall-clock 延迟不超过 N 帧。<br>
 * 设计要点：dt 以 double 累计消除逐帧 float 漂移；新市场首次出现立即获得一次真实推进，
 * 不滞后；{@code interval=1} 等价于关闭降频（每帧原样转发）。<br>
 * 线程模型：{@code Economy.advance} 仅在战役主线程执行，实现单线程确定。
 */
public final class MarketAdvanceThrottleHelper {
    /** 降频间隔系统属性名。 */
    public static final String INTERVAL_PROPERTY = "ssoptimizer.econ.advance.interval";
    /** 默认降频间隔：每 2 次经济推进转发一次真实 {@code advance}。 */
    public static final int DEFAULT_INTERVAL = 2;

    private static final Logger LOGGER = Logger.getLogger(MarketAdvanceThrottleHelper.class);

    private static final int ADVANCE_INTERVAL = parseInterval(System.getProperty(INTERVAL_PROPERTY));

    private MarketAdvanceThrottleHelper() {
    }

    /**
     * @return 生效的降频间隔（类初始化时解析一次），供并行调度器复用同一配置值
     */
    static int advanceInterval() {
        return ADVANCE_INTERVAL;
    }

    /**
     * 按配置的降频间隔处理一次市场推进请求。
     *
     * @param bridge 目标市场的降频状态桥接
     * @param amount 本次请求推进的时长（秒）
     */
    public static void advanceThrottled(final MarketAdvanceThrottleBridge bridge, final float amount) {
        advanceThrottled(bridge, amount, ADVANCE_INTERVAL);
    }

    /**
     * 按指定降频间隔处理一次市场推进请求。
     * <p>
     * 转发节奏：新市场第 1 次调用立即真实推进；此后每 {@code interval} 次调用
     * 以累计 dt 转发一次真实推进。任意时刻「已转发 dt 之和 + 待推进 dt」
     * 恒等于全部请求 dt 之和，不丢不重。
     *
     * @param bridge   目标市场的降频状态桥接
     * @param amount   本次请求推进的时长（秒）
     * @param interval 降频间隔（≥1，1 表示逐帧转发）
     */
    public static void advanceThrottled(final MarketAdvanceThrottleBridge bridge,
                                        final float amount, final int interval) {
        final double dt = decideAdvanceSeconds(bridge, amount, interval);
        if (!Double.isNaN(dt)) {
            bridge.ssoptimizer$advanceNow((float) dt);
        }
    }

    /**
     * 降频判定：累计本次请求的 dt 并决定本帧是否真实推进。
     * <p>
     * 与 {@link #advanceThrottled} 的判定逻辑完全一致，但把「执行真实推进」留给
     * 调用方（市场并行调度器据此把判定通过的市场投递到工作线程）。
     * 判定与状态更新（调用计数、累计待推进时长）必须在 {@code Economy.advance}
     * 市场循环所在的主线程执行——{@code MarketAdvanceThrottleBridge} 的状态
     * 无任何同步原语。
     *
     * @param bridge   目标市场的降频状态桥接
     * @param amount   本次请求推进的时长（秒）
     * @param interval 降频间隔（≥1，1 表示逐帧转发）
     * @return 本帧应真实推进的累计 dt（秒）；本帧被降频合并不推进时返回
     *         {@link Double#NaN}（dt 恒为非负，NaN 是无歧义的「跳过」哨兵）
     */
    public static double decideAdvanceSeconds(final MarketAdvanceThrottleBridge bridge,
                                              final float amount, final int interval) {
        final int calls = bridge.ssoptimizer$getAdvanceCallCount() + 1;
        bridge.ssoptimizer$setAdvanceCallCount(calls);

        // 新市场首次出现立即获得一次真实推进，不滞后
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

    /**
     * 解析降频间隔属性值。
     * <p>
     * 未设置时返回 {@link #DEFAULT_INTERVAL}；非法取值（不可解析或 ≤0）按 1
     * （逐帧转发，等价关闭降频）处理并记 WARN 日志。生产环境仅在类初始化时
     * 调用一次，WARN 最多出现一次。
     *
     * @param raw 属性原始值（可为 {@code null}）
     * @return 生效的降频间隔（≥1）
     */
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
