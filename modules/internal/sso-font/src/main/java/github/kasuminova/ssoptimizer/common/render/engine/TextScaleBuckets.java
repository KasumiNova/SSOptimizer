package github.kasuminova.ssoptimizer.common.render.engine;

import java.util.Locale;

/**
 * 运行时文本缩放的稳定缓存 bucket 量化器（跨包共享）。
 * <p>
 * 动机：动态字形缓存（运行时缩放字体、P3 的 TTF 动态图集）不能以浮点 scale 原值
 * 作缓存 key——逐帧微小波动会把缓存打爆；统一量化到 0.5~4.0、步进 0.125 的
 * 离散档位后按档位缓存/栅格化。
 * P3 起本类同时服务 {@code common.font.atlas} 的 (face, sizeBucket) 图集分组，
 * 故提升为 public。
 */
public final class TextScaleBuckets {
    private static final float DEFAULT_SCALE = 1.0f;
    private static final float MIN_SCALE     = 0.5f;
    private static final float MAX_SCALE     = 4.0f;
    private static final float STEP          = 0.125f;

    private TextScaleBuckets() {
    }

    /**
     * 把任意缩放值钳制到 [0.5, 4.0] 并按 0.125 步进量化；非有限值回退 1.0。
     */
    public static float bucketScale(final float scale) {
        if (!Float.isFinite(scale)) {
            return DEFAULT_SCALE;
        }

        final float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        return Math.round(clamped / STEP) * STEP;
    }

    /**
     * {@link #bucketScale(float)} 的千分位整数形式（缓存 key 用，避免浮点相等性问题）。
     */
    public static int bucketScaleMillis(final float scale) {
        return Math.round(bucketScale(scale) * 1000.0f);
    }

    /**
     * 千分位 bucket 的可读标签（如 1125 → "1.125"），诊断日志用。
     */
    public static String bucketLabel(final int scaleMillis) {
        return String.format(Locale.ROOT, "%.3f", scaleMillis / 1000.0f);
    }

    /**
     * 量化后是否等同 1.0（未缩放）。
     */
    public static boolean isIdentityScale(final float scale) {
        return Math.abs(bucketScale(scale) - DEFAULT_SCALE) <= 0.001f;
    }

    /**
     * 描边宽度量化（TTF 动态图集描边剪影）：逻辑像素 × bucketScale 换算到设备像素后
     * 按 0.5px 步进量化，返回量化后的设备像素宽度。
     * <p>
     * 一致性契约：图集缓存 key（strokeBucketMillis）、native 栅格化入参、
     * {@code TtfGlyphProvider.composeToFontBox} 的画布外扩、{@code TextLayoutEngine}
     * 剪影 quad 的几何外扩（量化值 ÷ bucketScale 折回逻辑坐标）四处必须使用同一个
     * 量化设备值，否则画布与几何错位（描边裁剪或露缝）。
     */
    public static float quantizeStrokeDevicePx(final float strokeWidthLogicalPx, final float bucketScale) {
        return Math.round(strokeWidthLogicalPx * bucketScale * 2f) * 0.5f;
    }
}
