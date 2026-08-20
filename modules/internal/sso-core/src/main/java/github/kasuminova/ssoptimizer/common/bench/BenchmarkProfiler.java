package github.kasuminova.ssoptimizer.common.bench;

import one.profiler.AsyncProfiler;
import one.profiler.Counter;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * async-profiler 采样接口。
 *
 * <p>native 库（libasyncProfiler）随 coremod jar 内嵌（linux-x64 / linux-arm64 / macos），
 * 首次启动时按平台提取到临时目录并以显式路径加载；采样数据以 JFR 与 collapsed 两种
 * 格式写入基准输出目录，前者可在 IDEA 中直接打开，后者用于脚本化热点分析。</p>
 */
public final class BenchmarkProfiler {
    private static final Logger LOGGER = Logger.getLogger(BenchmarkProfiler.class);

    private final AsyncProfiler profiler;
    private final Path jfrPath;
    private final Path collapsedPath;
    private boolean running;

    private BenchmarkProfiler(final AsyncProfiler profiler, final Path jfrPath, final Path collapsedPath) {
        this.profiler = profiler;
        this.jfrPath = jfrPath;
        this.collapsedPath = collapsedPath;
    }

    /**
     * 按当前平台加载 native 库并创建采样器。
     *
     * @param outputDir 输出目录
     * @return 可用时返回采样器实例；平台不支持或 native 缺失时返回 {@code null}（已记日志）
     */
    public static BenchmarkProfiler create(final Path outputDir) {
        final String resourceDir = platformResourceDir();
        if (resourceDir == null) {
            LOGGER.warn("[SSO-Bench] async-profiler does not support this platform, profiler disabled: "
                    + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
            return null;
        }

        final String resourcePath = '/' + resourceDir + "/libasyncProfiler.so";
        try (InputStream in = BenchmarkProfiler.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                LOGGER.warn("[SSO-Bench] async-profiler native library not found in jar: " + resourcePath);
                return null;
            }
            final Path libPath = Files.createTempDirectory("ssoptimizer-asyncprofiler")
                    .resolve(resourceDir).resolve("libasyncProfiler.so");
            Files.createDirectories(libPath.getParent());
            Files.copy(in, libPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            final AsyncProfiler profiler = AsyncProfiler.getInstance(libPath.toString());
            LOGGER.info("[SSO-Bench] async-profiler loaded: " + profiler.getVersion());
            return new BenchmarkProfiler(profiler,
                    outputDir.resolve("bench-profile.jfr"),
                    outputDir.resolve("bench-profile.collapsed.txt"));
        } catch (IOException | IllegalStateException e) {
            LOGGER.warn("[SSO-Bench] failed to load async-profiler, profiler disabled", e);
            return null;
        }
    }

    /**
     * 启动采样。
     *
     * @param event 采样事件（cpu / wall / alloc 等）
     */
    public void start(final String event) {
        try {
            Files.createDirectories(jfrPath.getParent());
            profiler.execute("start,event=" + event + ",interval=2ms,jfr,file=" + jfrPath.toAbsolutePath());
            running = true;
            LOGGER.info("[SSO-Bench] profiler started: event=" + event + " -> " + jfrPath.toAbsolutePath());
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            LOGGER.warn("[SSO-Bench] failed to start profiler", e);
        }
    }

    /** 停止采样并写出 JFR 与 collapsed 报告。 */
    public void stopAndDump() {
        if (!running) {
            return;
        }
        running = false;
        try {
            Files.writeString(collapsedPath, profiler.dumpCollapsed(Counter.SAMPLES));
            LOGGER.info("[SSO-Bench] collapsed profile written: " + collapsedPath.toAbsolutePath());
        } catch (IOException | IllegalStateException e) {
            LOGGER.warn("[SSO-Bench] failed to dump collapsed profile", e);
        }
        try {
            profiler.stop();
            LOGGER.info("[SSO-Bench] jfr profile written: " + jfrPath.toAbsolutePath());
        } catch (IllegalStateException e) {
            LOGGER.warn("[SSO-Bench] failed to stop profiler", e);
        }
    }

    private static String platformResourceDir() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            return arch.contains("aarch64") || arch.contains("arm") ? "linux-arm64" : "linux-x64";
        }
        if (os.contains("mac")) {
            return "macos";
        }
        return null;
    }
}
