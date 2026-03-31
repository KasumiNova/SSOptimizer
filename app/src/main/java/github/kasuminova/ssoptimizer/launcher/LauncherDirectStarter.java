package github.kasuminova.ssoptimizer.launcher;

import org.apache.log4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Uses the launcher's native static game-start method directly, bypassing the
 * launcher UI, so smoke tests can exercise real game loading without racing the
 * launcher's own UI/GL lifecycle.
 */
public final class LauncherDirectStarter {
    public static final String AUTO_START_PROPERTY            = "ssoptimizer.launcher.autostart";
    public static final String AUTO_START_RESOLUTION_PROPERTY = "ssoptimizer.launcher.autostart.res";
    public static final String AUTO_START_FULLSCREEN_PROPERTY = "ssoptimizer.launcher.autostart.fullscreen";
    public static final String AUTO_START_SOUND_PROPERTY      = "ssoptimizer.launcher.autostart.sound";

    private static final Logger LOGGER = Logger.getLogger(LauncherDirectStarter.class);

    private LauncherDirectStarter() {
    }

    public static boolean tryDirectStart(final String configuredResolution) {
        if (!Boolean.getBoolean(AUTO_START_PROPERTY)) {
            return false;
        }

        final String resolution = resolveResolution(configuredResolution);
        final String[] dimensions = splitResolution(resolution);
        if (dimensions == null) {
            LOGGER.warn("[SSOptimizer] Launcher auto-start requested but no valid resolution was available: " + resolution);
            return false;
        }

        final boolean fullscreen = resolveBoolean("startFS", AUTO_START_FULLSCREEN_PROPERTY, false);
        final boolean sound = resolveBoolean("startSound", AUTO_START_SOUND_PROPERTY, true);

        try {
            final Class<?> launcherClass = Class.forName("com.fs.starfarer.StarfarerLauncher");
            final Method method = launcherClass.getDeclaredMethod("o00000",
                    boolean.class, boolean.class, String.class, String.class);
            method.setAccessible(true);
            method.invoke(null, fullscreen, sound, dimensions[0], dimensions[1]);
            LOGGER.info("[SSOptimizer] Auto-started game loading through direct launcher path: "
                    + dimensions[0] + 'x' + dimensions[1]
                    + ", fullscreen=" + fullscreen
                    + ", sound=" + sound);
            return true;
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.warn("[SSOptimizer] Direct launcher auto-start failed", cause);
            return false;
        } catch (ReflectiveOperationException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve launcher direct-start method", e);
            return false;
        }
    }

    private static String resolveResolution(final String configuredResolution) {
        if (configuredResolution != null && !configuredResolution.isBlank()) {
            return configuredResolution.trim();
        }
        final String explicit = System.getProperty(AUTO_START_RESOLUTION_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        final String inherited = System.getProperty("startRes");
        if (inherited != null && !inherited.isBlank()) {
            return inherited.trim();
        }
        return "";
    }

    private static String[] splitResolution(final String resolution) {
        if (resolution == null || resolution.isBlank()) {
            return null;
        }
        final String[] parts = resolution.toLowerCase().split("x", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    private static boolean resolveBoolean(final String primaryProperty,
                                          final String fallbackProperty,
                                          final boolean defaultValue) {
        final String primary = System.getProperty(primaryProperty);
        if (primary != null && !primary.isBlank()) {
            return Boolean.parseBoolean(primary);
        }
        final String fallback = System.getProperty(fallbackProperty);
        if (fallback != null && !fallback.isBlank()) {
            return Boolean.parseBoolean(fallback);
        }
        return defaultValue;
    }
}