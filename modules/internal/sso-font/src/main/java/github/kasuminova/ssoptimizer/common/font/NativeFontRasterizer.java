package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Thin Java facade around the native FreeType rasterizer.
 * <p>
 * TTF 路径只保留 native 后端（P4 起）：库缺失或 JNI 符号不匹配时
 * {@link #isAvailable()} 为 false，调用方走显式回退（位图字形源/原版字体），
 * 不存在 Java2D 降级。
 */
public final class NativeFontRasterizer {
    private static final Logger LOGGER = Logger.getLogger(NativeFontRasterizer.class);

    public static final String RASTERIZER_PROPERTY     = "ssoptimizer.font.rasterizer";
    public static final String HINT_PROPERTY           = "ssoptimizer.font.hint";
    public static final String FORCE_AUTOHINT_PROPERTY = "ssoptimizer.font.forceautohint";

    private static volatile boolean availabilityChecked = false;
    private static volatile boolean available           = false;

    private NativeFontRasterizer() {
    }

    /**
     * 请求的后端模式。P4 起 TTF 路径仅 native 可用，本属性只影响缓存指纹与
     * 「显式要求 native 但不可用」时的诊断信息；{@code auto} 与 {@code native}
     * 之外的取值按 {@code auto} 处理。
     */
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

    /**
     * 栅格化指定码点的「描边剪影」：填充字形 ∪ 轮廓向外扩张 {@code strokeWidthPx} 像素的外环。
     * <p>
     * 合成语义：填充位图与描边外环位图按各自 bearing 求包围盒并集，逐像素取 alpha 最大值
     * （两者均为 8-bit 灰度覆盖率），输出并集尺寸的白底 alpha 位图。
     * {@code xAdvance} 与纯填充一致（描边不改变步进），{@code xOffset}/{@code yOffset}
     * 以并集包围盒为准。{@code strokeWidthPx <= 0} 或 native 描边器不可用时退化为纯填充结果。
     *
     * @param faceHandle    {@link #createFace} 返回的句柄
     * @param codePoint     Unicode code point
     * @param baseline      基线位置（与 {@link #rasterizeGlyph} 同语义）
     * @param strokeWidthPx 描边宽度（像素），{@code <= 0} 时等价于纯填充
     * @return 描边剪影位图；句柄非法或栅格化失败返回 {@code null}
     */
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

    /**
     * 批量栅格化一组码点，摊薄 JNI 边界开销。
     * <p>
     * {@code strokeWidthPx > 0} 时逐字走描边剪影合成（语义同
     * {@link #rasterizeGlyphStroked}），否则为纯填充。返回数组长度与 {@code codePoints}
     * 严格一致；单个码点栅格化失败（如字体无该字形）时对应元素为 {@code null}，
     * 不影响其余元素的栅格化。
     *
     * @param faceHandle    {@link #createFace} 返回的句柄
     * @param codePoints    待栅格化的 Unicode code point 数组
     * @param baseline      基线位置（与 {@link #rasterizeGlyph} 同语义）
     * @param strokeWidthPx 描边宽度（像素），{@code <= 0} 时为纯填充
     * @return 与入参等长的位图数组（失败元素为 {@code null}）；入参非法或整体失败返回 {@code null}
     */
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

    /**
     * 查询 face 是否含指定码点的字形（TTF 动态图集 face 链回退选择用，
     * 不改变任何栅格化语义）：{@code FT_Get_Char_Index(face, cp) != 0}。
     * 码点 0（.notdef 占位查询无意义）、句柄非法或 native 不可用时返回 false。
     *
     * @param faceHandle {@link #createFace} 返回的句柄
     * @param codePoint  Unicode code point
     * @return face 含该字形返回 true
     */
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