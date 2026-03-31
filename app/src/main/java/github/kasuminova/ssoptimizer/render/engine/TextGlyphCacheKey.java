package github.kasuminova.ssoptimizer.render.engine;

import java.util.Locale;

/**
 * Prototype runtime glyph-cache key for phase 2. It keeps the minimal identity
 * we can observe at the pre-quad layer without touching the original text
 * layout algorithm: font instance, nominal metrics, quantized scale, and glyph id.
 */
record TextGlyphCacheKey(int fontInstanceId,
                         int nominalFontSize,
                         int lineHeight,
                         int scaleBucketMillis,
                         int glyphId) {
    static TextGlyphCacheKey fromLayout(final int fontInstanceId,
                                        final int glyphId,
                                        final float scale,
                                        final int nominalFontSize,
                                        final int lineHeight) {
        return new TextGlyphCacheKey(
                fontInstanceId,
                nominalFontSize,
                lineHeight,
                TextScaleBuckets.bucketScaleMillis(scale),
                glyphId
        );
    }

    String summaryLabel() {
        return String.format(Locale.ROOT,
                "f%x/n%d/lh%d/s%d/g%d",
                fontInstanceId,
                nominalFontSize,
                lineHeight,
                scaleBucketMillis,
                glyphId);
    }
}