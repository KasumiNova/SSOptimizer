package github.kasuminova.ssoptimizer.common.logging;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LoadingNoiseLog4j2FilterTest {

    @Test
    void noiseInfoIsDeniedAndFlushedAsSummaryOnNextLog() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j2Filter filter = new LoadingNoiseLog4j2Filter(
                new LoadingNoiseAggregator(reports::add));

        assertEquals(Filter.Result.DENY, filter.filter(event("com.fs.starfarer.loading.LoadingUtils",
                Level.INFO, "Loading JSON from [data/weapons/abyss.wpn]")));
        assertEquals(Filter.Result.DENY, filter.filter(event("com.fs.starfarer.loading.LoadingUtils",
                Level.INFO, "Loading JSON from [data/weapons/asterism.wpn]")));
        assertEquals(Filter.Result.DENY, filter.filter(event("com.fs.starfarer.loading.LoadingUtils",
                Level.INFO, "Loading JSON from [data/weapons/naginata.wpn]")));
        assertTrue(reports.isEmpty(), "DENY 计数期间不得输出汇总行");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event(
                "github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin",
                Level.INFO, "[SSOptimizer] CoreMod loaded")));
        assertEquals(List.of("[SSOptimizer] Loaded 3 JSON files"), reports,
                "非目标日志到达时 flush 累计计数为汇总行");
    }

    @Test
    void warnAndErrorAreNeverSuppressedAndTriggerFlush() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j2Filter filter = new LoadingNoiseLog4j2Filter(
                new LoadingNoiseAggregator(reports::add));

        filter.filter(event("com.fs.starfarer.loading.SpecStore", Level.INFO, "Loading hullmod [敏捷护盾]"));
        filter.filter(event("com.fs.starfarer.loading.SpecStore", Level.INFO, "Loading hullmod [自适应相位线圈]"));

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("com.fs.starfarer.campaign.rules.Rules",
                Level.WARN, "rule not found")));
        assertEquals(List.of("[SSOptimizer] Loaded 2 hullmods"), reports,
                "WARN 到达即 flush 前一组累计（加载期结束信号）且自身放行");

        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("util.TextureData",
                Level.ERROR, "texture missing")));
        assertEquals(1, reports.size(), "计数已清空，后续 ERROR 不再重复输出统计");
    }

    @Test
    void nonNoiseAndNullEventsPassThrough() {
        final List<String> reports = new ArrayList<>();
        final LoadingNoiseLog4j2Filter filter = new LoadingNoiseLog4j2Filter(
                new LoadingNoiseAggregator(reports::add));

        assertEquals(Filter.Result.NEUTRAL, filter.filter(null));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("hullmods.No101_CoincidenceRangefinder",
                Level.INFO, "  Range bonus: 12.3%")));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("com.fs.starfarer.loading.ShipHullSpecLoader",
                Level.INFO, "Ship hull spec [LTHS] not found in ship_data.csv")));
        assertEquals(Filter.Result.NEUTRAL, filter.filter(event("com.fs.starfarer.loading.scripts.ScriptStore",
                Level.INFO, "Getting ready to load jar file [mods/foo.jar]")));
        assertTrue(reports.isEmpty(), "非噪音消息不产生计数或汇总行");
    }

    private static LogEvent event(String loggerName, Level level, String message) {
        return Log4jLogEvent.newBuilder()
                .setLoggerName(loggerName)
                .setLevel(level)
                .setMessage(new SimpleMessage(message))
                .setThreadName("test-thread")
                .setTimeMillis(System.currentTimeMillis())
                .build();
    }
}
