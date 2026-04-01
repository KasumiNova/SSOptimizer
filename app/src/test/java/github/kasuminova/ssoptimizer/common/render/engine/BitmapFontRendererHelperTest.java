package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BitmapFontRendererHelperTest {
    @Test
    void enablesVictorPixelSnappingOnlyForNonIntegralScale() {
        assertTrue(BitmapFontRendererHelper.shouldUsePixelSnappedVictorPath(true, 1.5f));
        assertTrue(BitmapFontRendererHelper.shouldUsePixelSnappedVictorPath(true, 1.25f));
        assertFalse(BitmapFontRendererHelper.shouldUsePixelSnappedVictorPath(true, 1.0f));
        assertFalse(BitmapFontRendererHelper.shouldUsePixelSnappedVictorPath(true, 2.0f));
        assertFalse(BitmapFontRendererHelper.shouldUsePixelSnappedVictorPath(false, 1.5f));
        assertTrue(BitmapFontRendererHelper.shouldUseOptimizedShadowPath(true, 1));
        assertFalse(BitmapFontRendererHelper.shouldUseOptimizedShadowPath(true, 0));
        assertFalse(BitmapFontRendererHelper.shouldUseOptimizedShadowPath(false, 2));
    }

    @Test
    void snapsVictorGlyphQuadToDevicePixels() {
        final BitmapFontRendererHelper.SnappedGlyphQuad quad = BitmapFontRendererHelper.snappedGlyphQuad(
                10.25f,
                30.75f,
                7,
                9,
                2,
                1.5f
        );

        assertEquals(10.0f, quad.left());
        assertEquals(28.0f, quad.top());
        assertEquals(21.0f, quad.right());
        assertEquals(14.0f, quad.bottom());
    }

    @Test
    void snappedGlyphQuadPreservesVisibleExtentForTinyGlyphs() {
        final BitmapFontRendererHelper.SnappedGlyphQuad quad = BitmapFontRendererHelper.snappedGlyphQuad(
                2.49f,
                4.51f,
                1,
                1,
                0,
                0.6f
        );

        assertEquals(2.0f, quad.left());
        assertEquals(5.0f, quad.top());
        assertEquals(3.0f, quad.right());
        assertEquals(4.0f, quad.bottom());
    }

    @Test
    void translatedShadowQuadKeepsGlyphSizeWhileOnlyOffsettingPosition() {
        final BitmapFontRendererHelper.SnappedGlyphQuad quad = BitmapFontRendererHelper.translatedGlyphQuad(
                10.0f,
                30.0f,
                8,
                10,
                2,
                1.5f,
                1.0f,
                1.0f
        );

        assertEquals(11.0f, quad.left());
        assertEquals(28.0f, quad.top());
        assertEquals(23.0f, quad.right());
        assertEquals(13.0f, quad.bottom());
    }
}
