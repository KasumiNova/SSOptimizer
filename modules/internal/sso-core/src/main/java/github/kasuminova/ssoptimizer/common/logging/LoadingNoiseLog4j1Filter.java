package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;

/**
 * 加载期噪音日志的 log4j 1.x 层聚合过滤器（真实 log4j-1.2.17 端到端验证用）。
 *
 * <p>与 {@link LoadingNoiseLog4j2Filter} 等语义：WARN/ERROR 不聚合不压制；INFO 命中聚合
 * 模式（{@link LoadingNoiseAggregator#NOISE_PATTERNS}）返回 DENY 并计数；非聚合消息或
 * WARN/ERROR 到达时 flush 累计计数后放行。</p>
 *
 * <p>注意：本类<b>不进入生产链路</b>——游戏运行时由 log4j-1.2-api 桥接转发到 log4j2，
 * 桥接的 {@code Category.callAppenders} 在 log4j2 core 存在时直接映射到 log4j2 Logger，
 * 1.x 的 appender Filter 链被绕过。本类的用途是让
 * {@code tools/verify_vanilla_log_noise_filter.sh}（独立 JVM + 真实 log4j-1.2.17）可以
 * 端到端验证过滤与聚合行为契约；生产挂载走 {@link LoadingNoiseLog4j2Filter}。</p>
 */
public final class LoadingNoiseLog4j1Filter extends Filter {
    /** 汇总行输出 logger（SSOptimizer 自身命名空间）。 */
    private static final org.apache.log4j.Logger LOGGER =
            org.apache.log4j.Logger.getLogger(LoadingNoiseLog4j1Filter.class);

    private final LoadingNoiseAggregator aggregator;

    /** 生产构造：汇总行经 SSOptimizer 自身 logger 输出。 */
    public LoadingNoiseLog4j1Filter() {
        this(new LoadingNoiseAggregator(LOGGER::info));
    }

    /** 包级可见：注入 reporter 供单测捕获汇总行。 */
    LoadingNoiseLog4j1Filter(LoadingNoiseAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public int decide(LoggingEvent event) {
        if (event == null) {
            return NEUTRAL;
        }
        final Level level = event.getLevel();
        if (level != null && level.toInt() >= Level.WARN_INT) {
            // WARN/ERROR：先 flush 累计统计（加载期结束信号），自身不压制
            aggregator.flush();
            return NEUTRAL;
        }
        if (aggregator.decideSuppress(event.getRenderedMessage())) {
            return DENY;
        }
        // 非聚合 INFO：flush 前一组计数后放行
        aggregator.flush();
        return NEUTRAL;
    }
}
