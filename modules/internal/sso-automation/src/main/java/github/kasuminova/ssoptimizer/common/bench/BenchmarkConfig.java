package github.kasuminova.ssoptimizer.common.bench;

import java.nio.file.Path;

public record BenchmarkConfig(boolean enabled,
                              String missionId,
                              int durationSec,
                              int warmupSec,
                              int screenshotIntervalSec,
                              boolean profilerEnabled,
                              String profilerEvent,
                              Path outputDir,
                              boolean exitWhenDone) {
    public static final String ENABLED_PROPERTY = "ssoptimizer.bench.enabled";

    public static final String MISSION_PROPERTY = "ssoptimizer.bench.mission";

    public static final String DURATION_SEC_PROPERTY = "ssoptimizer.bench.durationSec";

    public static final String WARMUP_SEC_PROPERTY = "ssoptimizer.bench.warmupSec";

    public static final String SCREENSHOT_INTERVAL_SEC_PROPERTY = "ssoptimizer.bench.screenshotIntervalSec";

    public static final String PROFILER_ENABLED_PROPERTY = "ssoptimizer.bench.profiler.enabled";

    public static final String PROFILER_EVENT_PROPERTY = "ssoptimizer.bench.profiler.event";

    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.bench.outputDir";

    public static final String EXIT_WHEN_DONE_PROPERTY = "ssoptimizer.bench.exit";

    public static final String DEFAULT_MISSION_ID = "gl_benchmark";

    public static BenchmarkConfig fromSystemProperties() {
        final boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
        final String missionId = System.getProperty(MISSION_PROPERTY, DEFAULT_MISSION_ID).trim();
        final int durationSec = parseIntProperty(DURATION_SEC_PROPERTY, 90);
        final int warmupSec = parseIntProperty(WARMUP_SEC_PROPERTY, 20);
        final int screenshotIntervalSec = parseIntProperty(SCREENSHOT_INTERVAL_SEC_PROPERTY, 15);
        final boolean profilerEnabled = Boolean.parseBoolean(System.getProperty(PROFILER_ENABLED_PROPERTY, "true"));
        final String profilerEvent = System.getProperty(PROFILER_EVENT_PROPERTY, "wall").trim();
        final String explicitOutputDir = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        final Path outputDir = explicitOutputDir.isEmpty()
                ? Path.of(System.getProperty("user.dir", "."), "ssoptimizer-bench-output")
                : Path.of(explicitOutputDir);
        final boolean exitWhenDone = Boolean.parseBoolean(System.getProperty(EXIT_WHEN_DONE_PROPERTY, "true"));
        return new BenchmarkConfig(enabled, missionId, durationSec, warmupSec, screenshotIntervalSec,
                profilerEnabled, profilerEvent, outputDir, exitWhenDone);
    }

    private static int parseIntProperty(final String property, final int defaultValue) {
        final String value = System.getProperty(property, "").trim();
        if (value.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
