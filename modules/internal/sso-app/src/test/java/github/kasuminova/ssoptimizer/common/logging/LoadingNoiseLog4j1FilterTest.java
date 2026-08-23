package github.kasuminova.ssoptimizer.common.logging;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 log4j 1.x 层聚合过滤器（真实 log4j-1.2.17 端到端验证脚本的适配层，不进生产链路）：
 * 真实构造 {@link LoggingEvent} 走 {@code decide(LoggingEvent)} 逻辑。
 *
 * <p>汇总输出经注入的 reporter 收集，不依赖真实 appender。</p>
 */
class LoadingNoiseLog4j1FilterTest {

    @Test
    void noiseInfoIsDeniedAndFlushedAsSummaryOnNextLog() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j1Filter filter = new LoadingNoiseLog4j1Filter(
                new LoadingNoiseAggregator(reports::add));

        assertEquals(Filter.DENY, filter.decide(event(Level.INFO, "Loading JSON from [a]")));
        assertEquals(Filter.DENY, filter.decide(event(Level.INFO, "Loading JSON from [b]")));
        assertEquals(Filter.DENY, filter.decide(event(Level.INFO, "Loading rule: defaultOpenDialog")));

        // 非聚合 INFO 到达：flush 前一组计数并放行自身
        assertEquals(Filter.NEUTRAL, filter.decide(event(Level.INFO, "[SSOptimizer] Loaded on Java 25")));
        assertEquals(List.of(
                "[SSOptimizer] Loaded 2 JSON files",
                "[SSOptimizer] Loaded 1 rules"
        ), reports);
    }

    @Test
    void warnAndErrorAreNeverSuppressed() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j1Filter filter = new LoadingNoiseLog4j1Filter(
                new LoadingNoiseAggregator(reports::add));

        filter.decide(event(Level.INFO, "Cleaned buffer for texture t (using reflection)"));

        assertEquals(Filter.NEUTRAL, filter.decide(event(Level.WARN, "rule not found")));
        assertEquals(Filter.NEUTRAL, filter.decide(event(Level.ERROR, "texture missing")));
        assertEquals(List.of("[SSOptimizer] Loaded 1 texture buffers"), reports,
                "WARN 到达即 flush 前一组累计且自身放行");
    }

    @Test
    void nonNoiseAndNullEventsPassThrough() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j1Filter filter = new LoadingNoiseLog4j1Filter(
                new LoadingNoiseAggregator(reports::add));

        assertEquals(Filter.NEUTRAL, filter.decide(null));
        assertEquals(Filter.NEUTRAL, filter.decide(event(Level.INFO, "Ship hull spec [LTHS] not found in ship_data.csv")));
        assertEquals(Filter.NEUTRAL, filter.decide(event(Level.INFO, "Getting ready to load jar file [mods/foo.jar]")));
        assertTrue(reports.isEmpty(), "非噪音消息不产生计数或汇总行");
    }

    private static LoggingEvent event(Level level, String message) {
        return new LoggingEvent("test.fqcn",
                Logger.getLogger("com.fs.starfarer.loading.LoadingUtils"),
                level, message, null);
    }
}
