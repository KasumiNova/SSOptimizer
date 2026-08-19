package github.kasuminova.ssoptimizer.common.automation;

import java.nio.file.Path;

/**
 * SSOptimizer 游戏内自动化配置。
 *
 * <p>该类只读取系统属性并形成不可变配置，供启动烟测、Mod 插件入口和验收脚本共享。
 * 业务逻辑集中在 {@code common/automation}，避免把自动化状态散落到启动脚本或注入代码中。</p>
 */
public record AutomationConfig(boolean enabled,
                               String scenario,
                               Path outputDir,
                               boolean requireScreenshotFile) {
    /** 自动化总开关系统属性。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.automation.enabled";

    /** 自动化场景系统属性。 */
    public static final String SCENARIO_PROPERTY = "ssoptimizer.automation.scenario";

    /** 自动化输出目录系统属性。 */
    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.automation.outputDir";

    /** 是否强制要求真实截图文件系统属性。 */
    public static final String REQUIRE_SCREENSHOT_FILE_PROPERTY = "ssoptimizer.automation.requireScreenshotFile";

    /** 首版 Arc Flare + AOD-7 自动化场景 ID。 */
    public static final String DEFAULT_SCENARIO = "arc_flare_aod7_basic";

    /** ASTD telemetry 文件名。 */
    public static final String ASTD_TELEMETRY_FILE = "astd-ingame-automation-telemetry.json";

    /**
     * 从当前 JVM 系统属性读取自动化配置。
     *
     * @return 自动化配置
     */
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

    /**
     * 返回 ASTD telemetry 的绝对路径。
     *
     * @return telemetry 文件路径
     */
    public Path astdTelemetryPath() {
        return outputDir.resolve(ASTD_TELEMETRY_FILE);
    }
}