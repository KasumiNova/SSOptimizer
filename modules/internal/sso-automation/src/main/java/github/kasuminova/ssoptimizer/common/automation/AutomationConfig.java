package github.kasuminova.ssoptimizer.common.automation;

import java.nio.file.Path;

public record AutomationConfig(boolean enabled,
                               String scenario,
                               Path outputDir,
                               boolean requireScreenshotFile) {
    public static final String ENABLED_PROPERTY = "ssoptimizer.automation.enabled";

    public static final String SCENARIO_PROPERTY = "ssoptimizer.automation.scenario";

    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.automation.outputDir";

    public static final String REQUIRE_SCREENSHOT_FILE_PROPERTY = "ssoptimizer.automation.requireScreenshotFile";

    public static final String DEFAULT_SCENARIO = "arc_flare_aod7_basic";

    public static final String ASTD_TELEMETRY_FILE = "astd-ingame-automation-telemetry.json";

    public static AutomationConfig fromSystemProperties() {
        final boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
        final String scenario = System.getProperty(SCENARIO_PROPERTY, DEFAULT_SCENARIO);
        final String explicitOutputDir = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        final Path outputDir = explicitOutputDir.isEmpty()
                ? Path.of(System.getProperty("user.dir", "."), "ssoptimizer-automation-output")
                : Path.of(explicitOutputDir);
        final boolean requireScreenshotFile = Boolean.parseBoolean(System.getProperty(REQUIRE_SCREENSHOT_FILE_PROPERTY, "false"));
        return new AutomationConfig(enabled, scenario, outputDir, requireScreenshotFile);
    }

    public Path astdTelemetryPath() {
        return outputDir.resolve(ASTD_TELEMETRY_FILE);
    }
}