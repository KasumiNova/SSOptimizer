package github.kasuminova.ssoptimizer.common.automation;

import org.apache.log4j.Logger;

public final class AutomationReporter {
    private static final Logger LOGGER = Logger.getLogger(AutomationReporter.class);

    private AutomationReporter() {
    }

    public static void logProfileIfEnabled() {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled()) {
            return;
        }
        LOGGER.info("[SSO-Automation] enabled scenario=" + config.scenario()
                + " outputDir=" + config.outputDir().toAbsolutePath()
                + " telemetry=" + config.astdTelemetryPath().toAbsolutePath()
                + " requireScreenshotFile=" + config.requireScreenshotFile());
    }
}