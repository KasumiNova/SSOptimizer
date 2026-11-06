package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

import java.nio.file.Path;
import java.util.Locale;

public final class NativeFontRasterizer {
    private static final Logger LOGGER = Logger.getLogger(NativeFontRasterizer.class);

    public static final String RASTERIZER_PROPERTY     = "ssoptimizer.font.rasterizer";
    public static final String HINT_PROPERTY           = "ssoptimizer.font.hint";
    public static final String FORCE_AUTOHINT_PROPERTY = "ssoptimizer.font.forceautohint";

    private static volatile boolean availabilityChecked = false;
    private static volatile boolean available           = false;

    private NativeFontRasterizer() {
    }

    public static RasterizerMode requestedMode() {
        final String configured = System.getProperty(RASTERIZER_PROPERTY, "auto");
        if (configured == null || configured.isBlank()) {
            return RasterizerMode.AUTO;
        }
        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "native" -> RasterizerMode.NATIVE;
            default -> RasterizerMode.AUTO;
        };
    }

    public static boolean isAvailable() {
        if (availabilityChecked) {
            return available;
        }

        synchronized (NativeFontRasterizer.class) {
            if (availabilityChecked) {
                return available;
            }

            boolean resolved = false;
            try {
                if (NativeRuntime.loadModule("font")) {
                    resolved = nativeIsAvailable();
                }
            } catch (UnsatisfiedLinkError | NoClassDefFoundError | SecurityException e) {
                LOGGER.warn("[SSOptimizer] Native font rasterizer load probe failed: " + e.getMessage());
                resolved = false;
            }

            available = resolved;
            availabilityChecked = true;
            return available;
        }
    }

    public static String describeSettings(final boolean antiAlias) {
        final HintMode hintMode = resolvedHintMode(antiAlias);
        final boolean forceAutoHint = resolvedForceAutoHint(antiAlias, hintMode);
        return "hint=" + hintMode.configValue()
                + ", forceAutoHint=" + forceAutoHint
                + ", antialias=" + antiAlias
                + ", embeddedBitmaps=false";
    }

    public static long createFace(final Path sourcePath,
                                  final float pixelSize,
                                  final boolean antiAlias) {
        if (sourcePath == null || !isAvailable() || !Float.isFinite(pixelSize) || pixelSize <= 0.0f) {
            return 0L;
        }

        try {
            final HintMode hintMode = resolvedHintMode(antiAlias);
            final boolean forceAutoHint = resolvedForceAutoHint(antiAlias, hintMode);
            return nativeCreateFace(
                    sourcePath.toAbsolutePath().normalize().toString(),
                    pixelSize,
                    hintMode.nativeCode(),
                    forceAutoHint,
                    antiAlias,
                    false
            );
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer createFace failed: " + e.getMessage());
            markUnavailable();
            return 0L;
        }
    }

    public static NativeGlyphBitmap rasterizeGlyph(final long faceHandle,
                                                   final int codePoint,
                                                   final int baseline) {
        if (faceHandle == 0L || !isAvailable()) {
            return null;
        }

        try {
            return nativeRasterizeGlyph(faceHandle, codePoint, baseline);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer rasterizeGlyph failed: " + e.getMessage());
            markUnavailable();
            return null;
        }
    }

    public static NativeGlyphBitmap rasterizeGlyphStroked(final long faceHandle,
                                                          final int codePoint,
                                                          final int baseline,
                                                          final float strokeWidthPx) {
        if (faceHandle == 0L || !isAvailable()) {
            return null;
        }

        try {
            return nativeRasterizeGlyphStroked(faceHandle, codePoint, baseline, strokeWidthPx);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer rasterizeGlyphStroked failed: " + e.getMessage());
            markUnavailable();
            return null;
        }
    }

    public static NativeGlyphBitmap[] rasterizeGlyphs(final long faceHandle,
                                                      final int[] codePoints,
                                                      final int baseline,
                                                      final float strokeWidthPx) {
        if (faceHandle == 0L || codePoints == null || !isAvailable()) {
            return null;
        }

        try {
            return nativeRasterizeGlyphs(faceHandle, codePoints, baseline, strokeWidthPx);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer rasterizeGlyphs failed: " + e.getMessage());
            markUnavailable();
            return null;
        }
    }

    public static boolean hasGlyph(final long faceHandle,
                                   final int codePoint) {
        if (faceHandle == 0L || codePoint == 0 || !isAvailable()) {
            return false;
        }

        try {
            return nativeHasGlyph(faceHandle, codePoint);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer hasGlyph failed: " + e.getMessage());
            markUnavailable();
            return false;
        }
    }

    public static void destroyFace(final long faceHandle) {
        if (faceHandle == 0L || !isAvailable()) {
            return;
        }

        try {
            nativeDestroyFace(faceHandle);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Native font rasterizer destroyFace failed: " + e.getMessage());
            markUnavailable();
        }
    }

    private static void markUnavailable() {
        available = false;
        availabilityChecked = true;
    }

    private static HintMode resolvedHintMode(final boolean antiAlias) {
        final String configured = System.getProperty(HINT_PROPERTY, "auto");
        if (configured == null || configured.isBlank()) {
            return antiAlias ? HintMode.LIGHT : HintMode.MONO;
        }

        return switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "light" -> HintMode.LIGHT;
            case "normal", "full" -> HintMode.NORMAL;
            case "mono", "monochrome" -> HintMode.MONO;
            case "none", "off" -> HintMode.NONE;
            default -> antiAlias ? HintMode.LIGHT : HintMode.MONO;
        };
    }

    private static boolean resolvedForceAutoHint(final boolean antiAlias,
                                                 final HintMode hintMode) {
        final String configured = System.getProperty(FORCE_AUTOHINT_PROPERTY, "auto");
        if (configured == null || configured.isBlank() || "auto".equalsIgnoreCase(configured)) {
            return antiAlias && hintMode == HintMode.LIGHT;
        }
        return Boolean.parseBoolean(configured);
    }

    private static native boolean nativeIsAvailable();

    private static native long nativeCreateFace(String fontPath,
                                                float pixelSize,
                                                int hintMode,
                                                boolean forceAutoHint,
                                                boolean antiAlias,
                                                boolean embeddedBitmaps);

    private static native NativeGlyphBitmap nativeRasterizeGlyph(long faceHandle,
                                                                 int codePoint,
                                                                 int baseline);

    private static native NativeGlyphBitmap nativeRasterizeGlyphStroked(long faceHandle,
                                                                        int codePoint,
                                                                        int baseline,
                                                                        float strokeWidthPx);

    private static native NativeGlyphBitmap[] nativeRasterizeGlyphs(long faceHandle,
                                                                    int[] codePoints,
                                                                    int baseline,
                                                                    float strokeWidthPx);

    private static native boolean nativeHasGlyph(long faceHandle,
                                                 int codePoint);

    private static native void nativeDestroyFace(long faceHandle);

    public enum RasterizerMode {
        AUTO,
        NATIVE
    }

    private enum HintMode {
        LIGHT(1, "light"),
        NORMAL(2, "normal"),
        MONO(3, "mono"),
        NONE(4, "none");

        private final int    nativeCode;
        private final String configValue;

        HintMode(final int nativeCode,
                 final String configValue) {
            this.nativeCode = nativeCode;
            this.configValue = configValue;
        }

        int nativeCode() {
            return nativeCode;
        }

        String configValue() {
            return configValue;
        }
    }
}