package github.kasuminova.ssoptimizer.common.bench;

import java.nio.file.Path;

/**
 * SSOptimizer 基准测试运行器配置。
 *
 * <p>只读取系统属性并形成不可变配置，供 Mixin hook 与驱动逻辑共享。
 * 基准测试流程：标题界面自动进入指定 mission（默认 GraphicsLib 的 gl_benchmark）→
 * 抑制部署对话框 → 预热 → 采样（截图 + async-profiler）→ 写汇总 → 退出。</p>
 */
public record BenchmarkConfig(boolean enabled,
                              String missionId,
                              int durationSec,
                              int warmupSec,
                              int screenshotIntervalSec,
                              boolean profilerEnabled,
                              String profilerEvent,
                              Path outputDir,
                              boolean exitWhenDone) {
    /** 基准测试总开关系统属性。 */
    public static final String ENABLED_PROPERTY = "ssoptimizer.bench.enabled";

    /** 目标 mission id 系统属性（默认 GraphicsLib 基准测试）。 */
    public static final String MISSION_PROPERTY = "ssoptimizer.bench.mission";

    /** 战斗内采样总时长（秒）系统属性。 */
    public static final String DURATION_SEC_PROPERTY = "ssoptimizer.bench.durationSec";

    /** 预热时长（秒）系统属性，预热结束后才启动 profiler。 */
    public static final String WARMUP_SEC_PROPERTY = "ssoptimizer.bench.warmupSec";

    /** 截图间隔（秒）系统属性，0 表示禁用周期截图。 */
    public static final String SCREENSHOT_INTERVAL_SEC_PROPERTY = "ssoptimizer.bench.screenshotIntervalSec";

    /** async-profiler 开关系统属性。 */
    public static final String PROFILER_ENABLED_PROPERTY = "ssoptimizer.bench.profiler.enabled";

    /** async-profiler 采样事件系统属性（cpu / wall 等）。 */
    public static final String PROFILER_EVENT_PROPERTY = "ssoptimizer.bench.profiler.event";

    /** 输出目录系统属性。 */
    public static final String OUTPUT_DIR_PROPERTY = "ssoptimizer.bench.outputDir";

    /** 采样结束后是否退出游戏进程系统属性。 */
    public static final String EXIT_WHEN_DONE_PROPERTY = "ssoptimizer.bench.exit";

    public static final String DEFAULT_MISSION_ID = "gl_benchmark";

    /**
     * 从当前 JVM 系统属性读取基准测试配置。
     *
     * @return 基准测试配置
     */
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
