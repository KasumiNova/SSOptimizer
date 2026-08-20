package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextGlyphCacheKeyTest {
    @Test
    void normalizesScaleIntoTypedCacheKey() {
        final TextGlyphCacheKey key = TextGlyphCacheKey.fromLayout(0x3e9, 65, 1.24f, 15, 19);

        assertEquals(0x3e9, key.fontInstanceId());
        assertEquals(15, key.nominalFontSize());
        assertEquals(19, key.lineHeight());
        assertEquals(1250, key.scaleBucketMillis());
        assertEquals(65, key.glyphId());
        assertEquals("f3e9/n15/lh19/s1250/g65", key.summaryLabel());
    }
}