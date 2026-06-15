package github.kasuminova.ssoptimizer.common.automation;

import org.apache.log4j.Logger;

/**
 * SSOptimizer 自动化 profile 的日志入口。
 *
 * <p>首版在 Mod 插件加载时记录自动化配置，烟测脚本据此确认 profile 生效。
 * 实际战斗 telemetry 由 ASTD dev-only 测试表面写入，SSOptimizer smoke test 负责读取和验收。</p>
 */
public final class AutomationReporter {
    private static final Logger LOGGER = Logger.getLogger(AutomationReporter.class);

    private AutomationReporter() {
    }

    /**
     * 如自动化 profile 已启用，记录配置和 telemetry 路径。
     */
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