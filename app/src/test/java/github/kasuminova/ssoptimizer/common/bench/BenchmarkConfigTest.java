package github.kasuminova.ssoptimizer.common.bench;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BenchmarkConfig} 的系统属性解析测试。
 */
class BenchmarkConfigTest {

    @AfterEach
    void clearProperties() {
        System.clearProperty(BenchmarkConfig.ENABLED_PROPERTY);
        System.clearProperty(BenchmarkConfig.MISSION_PROPERTY);
        System.clearProperty(BenchmarkConfig.DURATION_SEC_PROPERTY);
        System.clearProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY);
        System.clearProperty(BenchmarkConfig.SCREENSHOT_INTERVAL_SEC_PROPERTY);
        System.clearProperty(BenchmarkConfig.PROFILER_ENABLED_PROPERTY);
        System.clearProperty(BenchmarkConfig.PROFILER_EVENT_PROPERTY);
        System.clearProperty(BenchmarkConfig.OUTPUT_DIR_PROPERTY);
        System.clearProperty(BenchmarkConfig.EXIT_WHEN_DONE_PROPERTY);
    }

    @Test
    void defaultsAreDisabledWithGlBenchmarkMission() {
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        assertFalse(config.enabled());
        assertEquals("gl_benchmark", config.missionId());
        assertEquals(90, config.durationSec());
        assertEquals(20, config.warmupSec());
        assertEquals(15, config.screenshotIntervalSec());
        assertTrue(config.profilerEnabled());
        assertEquals("wall", config.profilerEvent());
        assertTrue(config.exitWhenDone());
    }

    @Test
    void explicitPropertiesAreParsed() {
        System.setProperty(BenchmarkConfig.ENABLED_PROPERTY, "true");
        System.setProperty(BenchmarkConfig.MISSION_PROPERTY, "gl_performance");
        System.setProperty(BenchmarkConfig.DURATION_SEC_PROPERTY, "120");
        System.setProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY, "30");
        System.setProperty(BenchmarkConfig.SCREENSHOT_INTERVAL_SEC_PROPERTY, "0");
        System.setProperty(BenchmarkConfig.PROFILER_ENABLED_PROPERTY, "false");
        System.setProperty(BenchmarkConfig.PROFILER_EVENT_PROPERTY, "cpu");
        System.setProperty(BenchmarkConfig.OUTPUT_DIR_PROPERTY, "/tmp/sso-bench-test");
        System.setProperty(BenchmarkConfig.EXIT_WHEN_DONE_PROPERTY, "false");

        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        assertTrue(config.enabled());
        assertEquals("gl_performance", config.missionId());
        assertEquals(120, config.durationSec());
        assertEquals(30, config.warmupSec());
        assertEquals(0, config.screenshotIntervalSec());
        assertFalse(config.profilerEnabled());
        assertEquals("cpu", config.profilerEvent());
        assertEquals("/tmp/sso-bench-test", config.outputDir().toString());
        assertFalse(config.exitWhenDone());
    }

    @Test
    void malformedIntegersFallBackToDefaults() {
        System.setProperty(BenchmarkConfig.DURATION_SEC_PROPERTY, "abc");
        System.setProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY, "");
        BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();
        assertEquals(90, config.durationSec());
        assertEquals(20, config.warmupSec());
    }
}
