package github.kasuminova.ssoptimizer.common.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.filter.AbstractFilter;

/**
 * log4j2 加载噪音过滤器，聚合匹配的 INFO，保留其他消息及 WARN/ERROR。
 * log4j-1.2-api 桥接会绕过 1.x Filter 链，因此实际运行时必须使用本过滤器。
 */
public final class LoadingNoiseLog4j2Filter extends AbstractFilter {
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger(LoadingNoiseLog4j2Filter.class);

    private final LoadingNoiseAggregator aggregator;

    public LoadingNoiseLog4j2Filter() {
        this(new LoadingNoiseAggregator(LOGGER::info));
    }

    LoadingNoiseLog4j2Filter(LoadingNoiseAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @Override
    public Result filter(LogEvent event) {
        if (event == null) {
            return Result.NEUTRAL;
        }
        if (!event.getLevel().isLessSpecificThan(Level.WARN)) {
            aggregator.flush();
            return Result.NEUTRAL;
        }
        final org.apache.logging.log4j.message.Message message = event.getMessage();
        final String rendered = message == null ? null : message.getFormattedMessage();
        if (VanillaLogNoiseConfigurator.isEngineLoadingLogger(event.getLoggerName())
                && aggregator.decideSuppress(rendered)) {
            return Result.DENY;
        }
        aggregator.flush();
        return Result.NEUTRAL;
    }
}
