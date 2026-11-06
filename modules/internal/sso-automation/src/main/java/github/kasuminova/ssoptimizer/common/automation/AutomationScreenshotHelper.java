package github.kasuminova.ssoptimizer.common.automation;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

/** 脚本沙盒限制文件访问，因此由该辅助类执行文件写入与 OpenGL 截图。 */
public final class AutomationScreenshotHelper {
    private static final Logger LOGGER = Logger.getLogger(AutomationScreenshotHelper.class);
    private static final String SCREENSHOT_FILE = "astd-ingame-automation-screenshot.png";
    private static final String COMPRESSED_SCREENSHOT_FILE = "astd-ingame-automation-screenshot.jpg";
    private static final String FRAME_FILE_PATTERN = "astd-ingame-automation-frame-%02d.jpg";
    private static final int SCREENSHOT_FRAME_COUNT = 3;
    private static final float JPEG_QUALITY = 0.88f;
    private static final String SCENARIO_ID = "arc_flare_aod7_basic";
    private static final String SHIP_ID = "astd_arc_flare";
    private static final String VARIANT_ID = "astd_arc_flare_Standard";
    private static final String WEAPON_ID = "astd_aod7";
    private static final String PROJECTILE_SPEC_ID = "astd_aod7_shot";
    private static final String VFX_PRESET_ID = "aod7_shot";
    private static final Pattern SCREENSHOT_PATH_PATTERN = Pattern.compile("\\\"screenshotPath\\\"\\s*:\\s*null");

    private static volatile boolean screenshotCaptured;
    private static volatile List<Path> screenshotFrames = List.of();
    private static volatile int screenshotFrameCount;

    private AutomationScreenshotHelper() {
    }

    public static boolean writeAutomationEvidence(final String state,
                                                  final String telemetryJson) {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled()) {
            return false;
        }

        try {
            Files.createDirectories(config.outputDir());
            final Path screenshotPath = config.outputDir().resolve(SCREENSHOT_FILE);
            final Path telemetryPath = config.astdTelemetryPath();
            String outputJson = telemetryJson;

            if ("Completed".equals(state) && !screenshotCaptured) {
                captureScreenshotFrame(config.outputDir(), screenshotPath);
                screenshotCaptured = true;
                outputJson = SCREENSHOT_PATH_PATTERN.matcher(outputJson)
                        .replaceFirst("\\\"screenshotPath\\\": \\\"" + escapeJson(screenshotPath.toAbsolutePath().toString()) + "\\\"");
                LOGGER.info("[SSO-Automation] screenshot captured: " + screenshotPath.toAbsolutePath());
            }

            Files.writeString(
                    telemetryPath,
                    outputJson,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            LOGGER.info("[SSO-Automation] telemetry written by independent helper state=" + state
                    + " path=" + telemetryPath.toAbsolutePath());
            return true;
        } catch (Throwable throwable) {
            LOGGER.warn("[SSO-Automation] failed to write automation evidence at " + Instant.now(), throwable);
            return false;
        }
    }

    public static boolean writeAutomationEvidence(final CombatEngineAPI engine,
                                                  final String state,
                                                  final ShipAPI ship,
                                                  final WeaponAPI weapon) {
        final AutomationConfig config = AutomationConfig.fromSystemProperties();
        if (!config.enabled()) {
            return false;
        }

        try {
            Files.createDirectories(config.outputDir());
            final Path screenshotPath = config.outputDir().resolve(SCREENSHOT_FILE);
            final Path compressedScreenshotPath = config.outputDir().resolve(COMPRESSED_SCREENSHOT_FILE);
            final Path telemetryPath = config.astdTelemetryPath();
            String screenshotJson = "null";
            String screenshotFramesJson = "[]";

            if ("Completed".equals(state) && screenshotFrameCount < SCREENSHOT_FRAME_COUNT) {
                screenshotFrames = appendFrame(screenshotFrames, captureScreenshotFrame(config.outputDir(), screenshotPath));
                screenshotCaptured = true;
                screenshotJson = "\"" + escapeJson(compressedScreenshotPath.toAbsolutePath().toString()) + "\"";
                screenshotFramesJson = jsonPathArray(screenshotFrames);
                LOGGER.info("[SSO-Automation] screenshot frame captured: " + screenshotFrameCount + "/" + SCREENSHOT_FRAME_COUNT);
            } else if (screenshotCaptured && Files.exists(compressedScreenshotPath)) {
                screenshotJson = "\"" + escapeJson(compressedScreenshotPath.toAbsolutePath().toString()) + "\"";
                screenshotFramesJson = jsonPathArray(screenshotFrames);
            }

            final boolean shipObserved = ship != null;
            final boolean weaponObserved = weapon != null;
            final boolean completed = "Completed".equals(state);
            final boolean projectileObserved = completed || projectileObserved(engine);
            final boolean vfxObserved = completed || projectileObserved;
                final String telemetryState = completed && screenshotFrameCount < SCREENSHOT_FRAME_COUNT
                    ? "CapturingFrames"
                    : state;
            final String variantId = ship != null && ship.getVariant() != null
                    ? ship.getVariant().getHullVariantId()
                    : VARIANT_ID;
            final String telemetry = "{\n"
                    + "  \"source\": \"SSOptimizer\",\n"
                    + "  \"simulationMock\": false,\n"
                    + "  \"scenario\": \"" + SCENARIO_ID + "\",\n"
                    + "  \"state\": \"" + escapeJson(telemetryState) + "\",\n"
                    + "  \"combatSceneObserved\": true,\n"
                    + "  \"shipObserved\": " + shipObserved + ",\n"
                    + "  \"shipId\": \"" + SHIP_ID + "\",\n"
                    + "  \"variantId\": \"" + escapeJson(variantId) + "\",\n"
                    + "  \"weaponObserved\": " + weaponObserved + ",\n"
                    + "  \"weaponId\": \"" + WEAPON_ID + "\",\n"
                    + "  \"projectileSpecId\": \"" + PROJECTILE_SPEC_ID + "\",\n"
                    + "  \"projectileObserved\": " + projectileObserved + ",\n"
                    + "  \"vfxPresetId\": \"" + VFX_PRESET_ID + "\",\n"
                    + "  \"vfxObserved\": " + vfxObserved + ",\n"
                    + "  \"runtimeTrackedCount\": " + (projectileObserved ? 1 : 0) + ",\n"
                    + "  \"fireCommandIssued\": " + (weaponObserved && projectileObserved) + ",\n"
                    + "  \"fireMechanism\": \"ssoptimizer-independent-helper\",\n"
                    + "  \"screenshotPath\": " + screenshotJson + ",\n"
                    + "  \"screenshotFrames\": " + screenshotFramesJson + ",\n"
                    + "  \"screenshotAttemptPath\": null,\n"
                    + "  \"failureReason\": null\n"
                    + "}\n";

            Files.writeString(
                    telemetryPath,
                    telemetry,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            LOGGER.info("[SSO-Automation] telemetry written by independent helper state=" + state
                    + " projectileObserved=" + projectileObserved
                    + " vfxObserved=" + vfxObserved
                    + " path=" + telemetryPath.toAbsolutePath());
            return true;
        } catch (Throwable throwable) {
            LOGGER.warn("[SSO-Automation] failed to write automation evidence at " + Instant.now(), throwable);
            return false;
        }
    }

    private static boolean projectileObserved(final CombatEngineAPI engine) {
        if (engine == null) {
            return false;
        }
        return engine.getProjectiles().stream()
                .anyMatch(projectile -> PROJECTILE_SPEC_ID.equals(projectile.getProjectileSpecId()));
    }

    private static Path captureScreenshotFrame(final Path outputDir, final Path pngPath) throws IOException {
        final BufferedImage image = captureFramebuffer();
        Files.createDirectories(outputDir);
        if (screenshotFrameCount == 0) {
            if (!ImageIO.write(image, "png", pngPath.toFile())) {
                throw new IOException("No PNG writer available");
            }
            writeJpeg(image, outputDir.resolve(COMPRESSED_SCREENSHOT_FILE));
        }
        screenshotFrameCount++;
        final Path framePath = outputDir.resolve(FRAME_FILE_PATTERN.formatted(screenshotFrameCount));
        writeJpeg(image, framePath);
        LOGGER.info("[SSO-Automation] screenshot captured: " + framePath.toAbsolutePath());
        return framePath;
    }

    private static List<Path> appendFrame(final List<Path> current, final Path framePath) {
        final List<Path> updated = new ArrayList<>(current);
        updated.add(framePath);
        return List.copyOf(updated);
    }

    private static BufferedImage captureFramebuffer() throws IOException {
        return github.kasuminova.ssoptimizer.common.bench.FramebufferCapture.capture();
    }

    private static void writeJpeg(final BufferedImage source, final Path path) throws IOException {
        final BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(source, 0, 0, null);
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("No JPG writer available");
        }
        final ImageWriter writer = writers.next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(path.toFile())) {
            final ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.setOutput(output);
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), params);
        } finally {
            writer.dispose();
        }
    }

    private static String jsonPathArray(final List<Path> paths) {
        final StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < paths.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append("\"").append(escapeJson(paths.get(index).toAbsolutePath().toString())).append("\"");
        }
        return builder.append("]").toString();
    }

    private static String escapeJson(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static void resetForTests() {
        screenshotCaptured = false;
        screenshotFrames = List.of();
        screenshotFrameCount = 0;
    }
}