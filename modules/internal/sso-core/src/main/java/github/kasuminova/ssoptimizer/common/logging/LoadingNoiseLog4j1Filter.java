package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;

/** log4j 1.x 的加载噪音过滤器，供独立日志环境的端到端验证使用。 */
public final class LoadingNoiseLog4j1Filter extends Filter {
    private static final org.apache.log4j.Logger LOGGER =
            org.apache.log4j.Logger.getLogger(LoadingNoiseLog4j1Filter.class);

    private final LoadingNoiseAggregator aggregator;

    public LoadingNoiseLog4j1Filter() {
        this(new LoadingNoiseAggregator(LOGGER::info));
    }

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
            aggregator.flush();
            return NEUTRAL;
        }
        if (aggregator.decideSuppress(event.getRenderedMessage())) {
            return DENY;
        }
        aggregator.flush();
        return NEUTRAL;
    }
}
