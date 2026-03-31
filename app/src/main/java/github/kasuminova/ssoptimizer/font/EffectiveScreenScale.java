package github.kasuminova.ssoptimizer.font;

import com.fs.starfarer.api.Global;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Resolves the effective UI/screen scale that should be used when generating
 * higher-resolution font atlases.
 */
public final class EffectiveScreenScale {
    public static final  String OVERRIDE_PROPERTY         = "ssoptimizer.font.screenscale.override";
    static final         String JAVA2D_UI_SCALE_PROPERTY  = "sun.java2d.uiScale";
    static final         String MODS_DIR_PROPERTY         = "com.fs.starfarer.settings.paths.mods";
    private static final String SCREEN_SCALE_OVERRIDE_KEY = "\"screenScaleOverride\"";
    private static final Object CONFIGURED_OVERRIDE_LOCK  = new Object();
    private static final Object CURRENT_SCALE_LOCK        = new Object();
    private static final long   CURRENT_SCALE_CACHE_NANOS = 1_000_000_000L;

    private static volatile Path  cachedSettingsFilePath;
    private static volatile long  cachedSettingsFileLastModifiedMillis = Long.MIN_VALUE;
    private static volatile float cachedSettingsFileOverrideScale      = 0.0f;
    private static volatile long  cachedCurrentScaleExpiresAtNanos     = Long.MIN_VALUE;
    private static volatile float cachedCurrentScale                   = 1.0f;

    private EffectiveScreenScale() {
    }

    public static float current() {
        final float overrideScale = parseScale(System.getProperty(OVERRIDE_PROPERTY));
        if (overrideScale > 0.0f) {
            return normalize(overrideScale);
        }

        final long now = System.nanoTime();
        if (now < cachedCurrentScaleExpiresAtNanos) {
            return cachedCurrentScale;
        }

        synchronized (CURRENT_SCALE_LOCK) {
            final long refreshedNow = System.nanoTime();
            if (refreshedNow < cachedCurrentScaleExpiresAtNanos) {
                return cachedCurrentScale;
            }

            final float resolved = computeCurrentScale();
            cachedCurrentScale = resolved;
            cachedCurrentScaleExpiresAtNanos = refreshedNow + CURRENT_SCALE_CACHE_NANOS;
            return resolved;
        }
    }

    static float resolve(final float gameScale,
                         final float desktopScale) {
        return normalize(Math.max(normalize(gameScale), normalize(desktopScale)));
    }

    static float parseScale(final String rawValue) {
        if (rawValue == null) {
            return 0.0f;
        }

        final String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return 0.0f;
        }

        try {
            if (normalized.endsWith("%")) {
                return Float.parseFloat(normalized.substring(0, normalized.length() - 1).trim()) / 100.0f;
            }
            if (normalized.endsWith("x")) {
                return Float.parseFloat(normalized.substring(0, normalized.length() - 1).trim());
            }
            return Float.parseFloat(normalized);
        } catch (NumberFormatException ignored) {
            return 0.0f;
        }
    }

    static float normalize(final float scale) {
        if (!Float.isFinite(scale) || scale <= 0.0f) {
            return 1.0f;
        }
        return scale;
    }

    static float resolveCurrent(final boolean gameSettingsAvailable,
                                final float gameScale,
                                final float desktopScale,
                                final float configuredOverrideScale) {
        if (gameSettingsAvailable) {
            return resolve(gameScale, desktopScale);
        }
        if (configuredOverrideScale > 0.0f) {
            return normalize(configuredOverrideScale);
        }
        return resolve(gameScale, desktopScale);
    }

    private static float computeCurrentScale() {
        final boolean gameSettingsAvailable = hasGameSettings();
        final float gameScale = gameScreenScale();
        final float desktopScale = desktopScreenScale();
        if (gameSettingsAvailable) {
            return resolve(gameScale, desktopScale);
        }
        return resolveCurrent(false, gameScale, desktopScale, configuredOverrideScale());
    }

    static float readConfiguredOverrideScale(final Path settingsFile) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
            return 0.0f;
        }

        try (BufferedReader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                final int keyStart = rawLine.indexOf(SCREEN_SCALE_OVERRIDE_KEY);
                if (keyStart < 0) {
                    continue;
                }

                final int colon = rawLine.indexOf(':', keyStart + SCREEN_SCALE_OVERRIDE_KEY.length());
                if (colon < 0) {
                    continue;
                }

                int valueEnd = rawLine.length();
                for (int i = colon + 1; i < rawLine.length(); i++) {
                    final char ch = rawLine.charAt(i);
                    if (ch == ',' || ch == '#' || ch == '}') {
                        valueEnd = i;
                        break;
                    }
                }

                final float parsed = parseScale(rawLine.substring(colon + 1, valueEnd));
                if (parsed > 0.0f) {
                    return normalize(parsed);
                }
                return 0.0f;
            }
        } catch (IOException ignored) {
            return 0.0f;
        }

        return 0.0f;
    }

    private static float gameScreenScale() {
        try {
            if (Global.getSettings() == null) {
                return 1.0f;
            }
            return normalize(Global.getSettings().getScreenScaleMult());
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static float desktopScreenScale() {
        final float java2dScale = parseScale(System.getProperty(JAVA2D_UI_SCALE_PROPERTY));
        final float transformScale = detectDefaultTransformScale();
        return normalize(Math.max(java2dScale, transformScale));
    }

    private static float configuredOverrideScale() {
        return readCachedConfiguredOverrideScale(resolveSettingsFile());
    }

    static float readCachedConfiguredOverrideScale(final Path settingsFile) {
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) {
            return 0.0f;
        }

        final long lastModifiedMillis = lastModifiedMillis(settingsFile);
        if (lastModifiedMillis < 0L) {
            return readConfiguredOverrideScale(settingsFile);
        }

        final Path cachedPath = cachedSettingsFilePath;
        if (settingsFile.equals(cachedPath) && cachedSettingsFileLastModifiedMillis == lastModifiedMillis) {
            return cachedSettingsFileOverrideScale;
        }

        synchronized (CONFIGURED_OVERRIDE_LOCK) {
            if (settingsFile.equals(cachedSettingsFilePath)
                    && cachedSettingsFileLastModifiedMillis == lastModifiedMillis) {
                return cachedSettingsFileOverrideScale;
            }

            final float resolved = readConfiguredOverrideScale(settingsFile);
            cachedSettingsFilePath = settingsFile;
            cachedSettingsFileLastModifiedMillis = lastModifiedMillis;
            cachedSettingsFileOverrideScale = resolved;
            return resolved;
        }
    }

    static void clearConfiguredOverrideCache() {
        synchronized (CONFIGURED_OVERRIDE_LOCK) {
            cachedSettingsFilePath = null;
            cachedSettingsFileLastModifiedMillis = Long.MIN_VALUE;
            cachedSettingsFileOverrideScale = 0.0f;
        }
        synchronized (CURRENT_SCALE_LOCK) {
            cachedCurrentScale = 1.0f;
            cachedCurrentScaleExpiresAtNanos = Long.MIN_VALUE;
        }
    }

    private static boolean hasGameSettings() {
        try {
            return Global.getSettings() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Path resolveSettingsFile() {
        final String configuredModsDir = System.getProperty(MODS_DIR_PROPERTY);
        if (configuredModsDir != null && !configuredModsDir.isBlank()) {
            final Path modsDir = Path.of(configuredModsDir).toAbsolutePath().normalize();
            final Path gameDir = modsDir.getParent();
            if (gameDir != null) {
                final Path configured = gameDir.resolve("data").resolve("config").resolve("settings.json").normalize();
                if (Files.isRegularFile(configured)) {
                    return configured;
                }
            }
        }

        final Path cwdCandidate = Path.of("").toAbsolutePath().normalize()
                                      .resolve("data")
                                      .resolve("config")
                                      .resolve("settings.json")
                                      .normalize();
        if (Files.isRegularFile(cwdCandidate)) {
            return cwdCandidate;
        }
        return null;
    }

    private static long lastModifiedMillis(final Path settingsFile) {
        try {
            return Files.getLastModifiedTime(settingsFile).toMillis();
        } catch (IOException ignored) {
            return -1L;
        }
    }

    static float detectDefaultTransformScale() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return 1.0f;
            }

            float maxScale = 1.0f;
            final GraphicsEnvironment environment = GraphicsEnvironment.getLocalGraphicsEnvironment();
            for (GraphicsDevice device : environment.getScreenDevices()) {
                final GraphicsConfiguration configuration = device.getDefaultConfiguration();
                if (configuration == null) {
                    continue;
                }
                maxScale = Math.max(maxScale, transformScale(configuration.getDefaultTransform()));
            }
            return normalize(maxScale);
        } catch (Throwable ignored) {
            return 1.0f;
        }
    }

    private static float transformScale(final AffineTransform transform) {
        if (transform == null) {
            return 1.0f;
        }

        final double scaleX = Math.abs(transform.getScaleX());
        final double scaleY = Math.abs(transform.getScaleY());
        final double scale = Math.max(scaleX, scaleY);
        if (!Double.isFinite(scale) || scale <= 0.0) {
            return 1.0f;
        }
        return (float) scale;
    }
}