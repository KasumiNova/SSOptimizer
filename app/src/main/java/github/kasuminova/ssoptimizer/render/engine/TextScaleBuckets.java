package github.kasuminova.ssoptimizer.render.engine;

import java.util.Locale;

/**
 * Quantizes runtime text scales into stable cache buckets. Current native text
 * rendering still samples pre-baked atlases, so dynamic glyph caching must key
 * by a normalized scale bucket instead of raw float values.
 */
final class TextScaleBuckets {
    private static final float DEFAULT_SCALE = 1.0f;
    private static final float MIN_SCALE     = 0.5f;
    private static final float MAX_SCALE     = 4.0f;
    private static final float STEP          = 0.125f;

    private TextScaleBuckets() {
    }

    static float bucketScale(final float scale) {
        if (!Float.isFinite(scale)) {
            return DEFAULT_SCALE;
        }

        final float clamped = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        return Math.round(clamped / STEP) * STEP;
    }

    static int bucketScaleMillis(final float scale) {
        return Math.round(bucketScale(scale) * 1000.0f);
    }

    static String bucketLabel(final int scaleMillis) {
        return String.format(Locale.ROOT, "%.3f", scaleMillis / 1000.0f);
    }

    static boolean isIdentityScale(final float scale) {
        return Math.abs(bucketScale(scale) - DEFAULT_SCALE) <= 0.001f;
    }
}
