package github.kasuminova.ssoptimizer.common.bench;

import com.fs.starfarer.loading.SpecStore;
import com.fs.starfarer.title.MissionDefinition;
import com.fs.starfarer.title.MissionSpec;
import com.fs.starfarer.title.TitleScreenState;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class BenchmarkDriver {
    private static final Logger LOGGER = Logger.getLogger(BenchmarkDriver.class);

    private static volatile BenchmarkConfig config;
    private static volatile boolean missionLaunched;
    private static volatile long lastMissingLogMs;

    private static boolean combatActive;
    private static long combatStartMs;
    private static long frameCount;
    private static long nextScreenshotMs;
    private static int screenshotIndex;
    private static BenchmarkProfiler profiler;
    private static boolean profilerStarted;
    private static final List<String> screenshots = new ArrayList<>();

    private BenchmarkDriver() {
    }

    public static boolean isEnabled() {
        return config().enabled();
    }

    public static void tryLaunchFromTitleScreen(final Object titleScreenState) {
        final BenchmarkConfig cfg = config();
        if (!cfg.enabled() || missionLaunched) {
            return;
        }
        if (!(titleScreenState instanceof TitleScreenState title)) {
            return;
        }

        for (MissionSpec spec : SpecStore.getMissionSpecs()) {
            if (!cfg.missionId().equals(spec.getId())) {
                continue;
            }
            title.missionAccepted(new MissionDefinition(spec));
            missionLaunched = true;
            LOGGER.info("[SSO-Bench] launched mission: " + cfg.missionId()
                    + " duration=" + cfg.durationSec() + "s warmup=" + cfg.warmupSec() + "s"
                    + " output=" + cfg.outputDir().toAbsolutePath());
            return;
        }

        final long now = System.currentTimeMillis();
        if (now - lastMissingLogMs > 10_000L) {
            lastMissingLogMs = now;
            LOGGER.warn("[SSO-Bench] mission not found in SpecStore: " + cfg.missionId()
                    + " (available: " + SpecStore.getMissionSpecs().size() + ")");
        }
    }

    public static void onCombatFrameEnd() {
        final BenchmarkConfig cfg = config();
        if (!cfg.enabled() || !missionLaunched) {
            return;
        }

        final long now = System.currentTimeMillis();
        if (!combatActive) {
            combatActive = true;
            combatStartMs = now;
            nextScreenshotMs = now;
            if (cfg.profilerEnabled() && cfg.durationSec() > cfg.warmupSec()) {
                profiler = BenchmarkProfiler.create(cfg.outputDir());
            }
            LOGGER.info("[SSO-Bench] combat entered, sampling for " + cfg.durationSec() + "s");
        }
        frameCount++;

        if (cfg.screenshotIntervalSec() > 0 && now >= nextScreenshotMs) {
            nextScreenshotMs = now + cfg.screenshotIntervalSec() * 1000L;
            captureScreenshot(cfg);
        }

        final long elapsedSec = (now - combatStartMs) / 1000L;
        if (!profilerStarted && profiler != null && elapsedSec >= cfg.warmupSec()) {
            profilerStarted = true;
            profiler.start(cfg.profilerEvent());
        }

        if (elapsedSec >= cfg.durationSec()) {
            finish(cfg, now);
        }
    }

    private static void captureScreenshot(final BenchmarkConfig cfg) {
        screenshotIndex++;
        try {
            final Path path = cfg.outputDir().resolve("frames")
                    .resolve(String.format("frame-%03d.png", screenshotIndex));
            FramebufferCapture.captureToPng(path);
            screenshots.add(path.toAbsolutePath().toString());
            LOGGER.info("[SSO-Bench] screenshot captured: " + path.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[SSO-Bench] screenshot failed", e);
        }
    }

    private static void finish(final BenchmarkConfig cfg, final long now) {
        if (profilerStarted && profiler != null) {
            profiler.stopAndDump();
        }
        captureScreenshot(cfg);

        final double elapsedSec = (now - combatStartMs) / 1000.0;
        final double avgFps = frameCount / elapsedSec;
        writeSummary(cfg, elapsedSec, avgFps);
        LOGGER.info("[SSO-Bench] completed: frames=" + frameCount
                + String.format(" avgFps=%.1f", avgFps) + " elapsed=" + (long) elapsedSec + "s");

        if (cfg.exitWhenDone()) {
            System.exit(0);
        }
        combatActive = false;
        missionLaunched = false;
        frameCount = 0;
        screenshotIndex = 0;
        screenshots.clear();
        profiler = null;
        profilerStarted = false;
    }

    private static void writeSummary(final BenchmarkConfig cfg, final double elapsedSec, final double avgFps) {
        final StringBuilder json = new StringBuilder("{\n");
        json.append("  \"mission\": \"").append(cfg.missionId()).append("\",\n");
        json.append("  \"durationSec\": ").append((long) elapsedSec).append(",\n");
        json.append("  \"warmupSec\": ").append(cfg.warmupSec()).append(",\n");
        json.append("  \"frames\": ").append(frameCount).append(",\n");
        json.append(String.format("  \"avgFps\": %.2f,\n", avgFps));
        json.append("  \"profilerJfr\": ").append(profilerStarted && profiler != null
                ? "\"" + cfg.outputDir().resolve("bench-profile.jfr").toAbsolutePath() + "\"" : "null").append(",\n");
        json.append("  \"profilerCollapsed\": ").append(profilerStarted && profiler != null
                ? "\"" + cfg.outputDir().resolve("bench-profile.collapsed.txt").toAbsolutePath() + "\"" : "null").append(",\n");
        json.append("  \"screenshots\": [");
        for (int i = 0; i < screenshots.size(); i++) {
            json.append(i == 0 ? "" : ", ").append('\"').append(screenshots.get(i)).append('\"');
        }
        json.append("]\n}\n");
        try {
            Files.createDirectories(cfg.outputDir());
            Files.writeString(cfg.outputDir().resolve("bench-summary.json"), json.toString());
        } catch (IOException e) {
            LOGGER.warn("[SSO-Bench] failed to write bench summary", e);
        }
    }

    private static BenchmarkConfig config() {
        BenchmarkConfig cached = config;
        if (cached == null) {
            cached = BenchmarkConfig.fromSystemProperties();
            config = cached;
        }
        return cached;
    }

    public static void resetForTests() {
        config = null;
        missionLaunched = false;
        lastMissingLogMs = 0;
        combatActive = false;
        combatStartMs = 0;
        frameCount = 0;
        nextScreenshotMs = 0;
        screenshotIndex = 0;
        profiler = null;
        profilerStarted = false;
        screenshots.clear();
    }
}
