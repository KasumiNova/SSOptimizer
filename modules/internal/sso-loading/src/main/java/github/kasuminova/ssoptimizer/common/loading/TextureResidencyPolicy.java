package github.kasuminova.ssoptimizer.common.loading;

/**
 * 懒加载与驻留管理的开关、阈值读取和纹理路径分类。只读取系统属性，不持有状态。
 */
final class TextureResidencyPolicy {
    private static final long DEFAULT_MIN_GPU_BYTES = 1L << 20;
    private static final long DEFAULT_TRACK_MIN_GPU_BYTES = 64L << 10;
    private static final long DEFAULT_IDLE_UNLOAD_MILLIS = 0L;
    private static final long DEFAULT_PREVIEW_PROTECT_MILLIS = 300_000L;
    private static final long DEFAULT_SWEEP_INTERVAL_MILLIS = 1_000L;
    private static final long DEFAULT_COMPOSITION_REPORT_INTERVAL_MILLIS = 5_000L;
    private static final long DEFAULT_MANAGEMENT_LOG_INTERVAL_MILLIS = 15_000L;
    private static final boolean DEFAULT_MINIMAL_STARTUP = true;
    private static final String GRAPHICS_PREFIX = "graphics/";
    private static final String[] PREVIEW_PROTECTED_PREFIXES = {
            "graphics/ships/",
            "graphics/stations/",
            "graphics/weapons/"
    };
    private static final String[] EAGER_PREFIXES = {
            "graphics/icons/",
            "graphics/ui/",
            "graphics/hud/",
            "graphics/cursors/",
            "graphics/fonts/",
            "graphics/warroom/"
    };

    private TextureResidencyPolicy() {
    }

    static String configuredCompositionReportPath() {
        final String configured = System.getProperty(LazyTextureManager.COMPOSITION_REPORT_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return LazyTextureManager.DEFAULT_COMPOSITION_REPORT_FILE;
        }
        return configured.trim();
    }

    static boolean isEnabled() {
        return TextureConversionCache.isEnabled() && !Boolean.getBoolean(LazyTextureManager.DISABLE_PROPERTY);
    }

    static boolean isHotReloadEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(LazyTextureManager.HOT_RELOAD_PROPERTY, "true"));
    }

    static boolean shouldDefer(final String resourcePath,
                               final int sourceBytes,
                               final long estimatedGpuBytes) {
        if (isMinimalStartupTexture(resourcePath, estimatedGpuBytes)) {
            return true;
        }
        if (estimatedGpuBytes < minimumGpuBytes()) {
            return false;
        }
        if (sourceBytes < 131_072
                && !resourcePath.startsWith("graphics/backgrounds/")
                && !resourcePath.startsWith("graphics/terrain/")
                && !resourcePath.startsWith("graphics/planets/")) {
            return false;
        }
        return !isAlwaysEager(resourcePath);
    }

    static boolean minimalStartupEnabled() {
        return Boolean.parseBoolean(System.getProperty(LazyTextureManager.MINIMAL_STARTUP_PROPERTY,
                Boolean.toString(DEFAULT_MINIMAL_STARTUP)));
    }

    static boolean isMinimalStartupTexture(final String resourcePath,
                                           final long estimatedGpuBytes) {
        return minimalStartupEnabled()
                && isManagedGraphicsTexture(resourcePath)
                && estimatedGpuBytes >= trackMinimumGpuBytes();
    }

    static boolean shouldTrackResidency(final String resourcePath,
                                        final int sourceBytes,
                                        final long estimatedGpuBytes) {
        if (!isManagedGraphicsTexture(resourcePath)) {
            return false;
        }
        return estimatedGpuBytes >= trackMinimumGpuBytes();
    }

    static long idleUnloadMillis() {
        return Math.max(0L, Long.getLong(
                LazyTextureManager.IDLE_UNLOAD_MILLIS_PROPERTY, DEFAULT_IDLE_UNLOAD_MILLIS));
    }

    static long effectiveIdleUnloadMillis(final String resourcePath) {
        final long baseIdleMillis = idleUnloadMillis();
        if (!isPreviewProtectedTexture(resourcePath)) {
            return baseIdleMillis;
        }

        final long protectedMillis = Math.max(0L, Long.getLong(
                LazyTextureManager.PREVIEW_PROTECT_MILLIS_PROPERTY, DEFAULT_PREVIEW_PROTECT_MILLIS));
        return Math.max(baseIdleMillis, protectedMillis);
    }

    static long trackMinimumGpuBytes() {
        final long configured = Long.getLong(
                LazyTextureManager.TRACK_MIN_GPU_BYTES_PROPERTY, DEFAULT_TRACK_MIN_GPU_BYTES);
        return Math.max(16_384L, configured);
    }

    static long sweepIntervalMillis() {
        final long configured = Long.getLong(
                LazyTextureManager.SWEEP_INTERVAL_MILLIS_PROPERTY, DEFAULT_SWEEP_INTERVAL_MILLIS);
        return Math.max(250L, configured);
    }

    static long compositionReportIntervalMillis() {
        final long configured = Long.getLong(LazyTextureManager.COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY,
                DEFAULT_COMPOSITION_REPORT_INTERVAL_MILLIS);
        return Math.max(0L, configured);
    }

    static long managementLogIntervalMillis() {
        final long configured = Long.getLong(LazyTextureManager.MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY,
                DEFAULT_MANAGEMENT_LOG_INTERVAL_MILLIS);
        return Math.max(0L, configured);
    }

    static boolean isPreviewProtectedTexture(final String resourcePath) {
        final String normalized = TextureResourceLoader.normalizeResourcePath(resourcePath);
        for (final String prefix : PREVIEW_PROTECTED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static long minimumGpuBytes() {
        final long configured = Long.getLong(LazyTextureManager.MIN_GPU_BYTES_PROPERTY, DEFAULT_MIN_GPU_BYTES);
        return Math.max(262_144L, configured);
    }

    private static boolean isAlwaysEager(final String resourcePath) {
        for (final String prefix : EAGER_PREFIXES) {
            if (resourcePath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isManagedGraphicsTexture(final String resourcePath) {
        return resourcePath != null
                && !resourcePath.isEmpty()
                && resourcePath.startsWith(GRAPHICS_PREFIX)
                && !isAlwaysEager(resourcePath);
    }
}
