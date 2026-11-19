package github.kasuminova.ssoptimizer.common.automation;

import java.nio.file.Path;

public record AutomationConfig(boolean enabled,
                               String scenario,
                               Path outputDir) {
    public static final String ENABLED_PROPERTY = "ssoptimizer.automation.enabled";

    public static final String SCENARIO_PROPERTY = "ssoptimizer.automation.scenario";

    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.automation.outputDir";

    public static AutomationConfig fromSystemProperties() {
        final boolean enabled = Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false"));
        final String scenario = System.getProperty(SCENARIO_PROPERTY, "");
        final String explicitOutputDir = System.getProperty(OUTPUT_DIR_PROPERTY, "").trim();
        final Path outputDir = explicitOutputDir.isEmpty()
                ? Path.of(System.getProperty("user.dir", "."), "ssoptimizer-automation-output")
                : Path.of(explicitOutputDir);
        return new AutomationConfig(enabled, scenario, outputDir);
    }
}