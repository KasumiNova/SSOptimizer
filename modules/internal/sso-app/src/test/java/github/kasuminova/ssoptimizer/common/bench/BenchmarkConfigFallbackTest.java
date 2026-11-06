package github.kasuminova.ssoptimizer.common.bench;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BenchmarkConfigFallbackTest {

    @Test
    void malformedIntegersFallBackToDefaults() {
        String originalDuration = System.getProperty(BenchmarkConfig.DURATION_SEC_PROPERTY);
        String originalWarmup = System.getProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY);
        try {
            System.clearProperty(BenchmarkConfig.DURATION_SEC_PROPERTY);
            System.clearProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY);
            BenchmarkConfig defaults = BenchmarkConfig.fromSystemProperties();

            System.setProperty(BenchmarkConfig.DURATION_SEC_PROPERTY, "abc");
            System.setProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY, "");
            BenchmarkConfig config = BenchmarkConfig.fromSystemProperties();

            assertEquals(defaults.durationSec(), config.durationSec());
            assertEquals(defaults.warmupSec(), config.warmupSec());
        } finally {
            restoreProperty(BenchmarkConfig.DURATION_SEC_PROPERTY, originalDuration);
            restoreProperty(BenchmarkConfig.WARMUP_SEC_PROPERTY, originalWarmup);
        }
    }

    private static void restoreProperty(String key, String original) {
        if (original == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, original);
        }
    }
}
