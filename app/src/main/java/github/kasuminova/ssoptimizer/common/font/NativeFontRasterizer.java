package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Thin Java facade around the optional native FreeType rasterizer.
 * <p>
 * The native backend is opportunistic: if the library is missing or does not
 * export the expected JNI symbols, callers transparently fall back to Java2D.
 */
public final class NativeFontRasterizer {
    public static final String RASTERIZER_PROPERTY      = "ssoptimizer.font.rasterizer";
    public static final String HINT_PROPERTY            = "ssoptimizer.font.hint";
    public static final String FORCE_AUTOHINT_PROPERTY  = "ssoptimizer.font.forceautohint";

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
            case "java2d", "java" -> RasterizerMode.JAVA2D;
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
                NativeRuntime.ensureLoaded();
                if (NativeRuntime.isLoaded()) {
                    resolved = nativeIsAvailable();
                }
            } catch (UnsatisfiedLinkError | NoClassDefFoundError | SecurityException ignored) {
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
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
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
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
            markUnavailable();
            return null;
        }
    }

    public static void destroyFace(final long faceHandle) {
        if (faceHandle == 0L || !isAvailable()) {
            return;
        }

        try {
            nativeDestroyFace(faceHandle);
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
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

    private static native void nativeDestroyFace(long faceHandle);

    public enum RasterizerMode {
        AUTO,
        NATIVE,
        JAVA2D
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