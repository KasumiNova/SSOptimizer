package github.kasuminova.ssoptimizer.common.render.engine;

import java.util.Locale;

public final class TextScaleBuckets {
    private static final float DEFAULT_SCALE = 1.0f;
    private static final float MIN_SCALE     = 0.5f;
    private static final float MAX_SCALE     = 4.0f;
    private static final float STEP          = 0.125f;

    private TextScaleBuckets() {
    }

    public static float bucketScale(final float scale) {
        if (!Float.isFinite(scale)) {
            return DEFAULT_SCALE;
        }

        final float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        return Math.round(clamped / STEP) * STEP;
    }

    public static int bucketScaleMillis(final float scale) {
        return Math.round(bucketScale(scale) * 1000.0f);
    }

    public static String bucketLabel(final int scaleMillis) {
        return String.format(Locale.ROOT, "%.3f", scaleMillis / 1000.0f);
    }

    public static boolean isIdentityScale(final float scale) {
        return Math.abs(bucketScale(scale) - DEFAULT_SCALE) <= 0.001f;
    }

    /** 缓存键、栅格化参数和画布外扩必须使用同一量化值，避免描边几何错位。 */
    public static float quantizeStrokeDevicePx(final float strokeWidthLogicalPx, final float bucketScale) {
        return Math.round(strokeWidthLogicalPx * bucketScale * 2f) * 0.5f;
    }
}
