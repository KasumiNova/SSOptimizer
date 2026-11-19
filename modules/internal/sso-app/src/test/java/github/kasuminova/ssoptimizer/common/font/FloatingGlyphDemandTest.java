package github.kasuminova.ssoptimizer.common.font;

import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static github.kasuminova.ssoptimizer.common.font.FloatingGlyphDemand.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class FloatingGlyphDemandTest {
    @Test
    void picksNearestReadySizeWithLargerTieAndRejectsDistantSizes() {
        var ready = new TreeMap<>(Map.of(875, "small", 1125, "large"));
        assertEquals("large", FloatingGlyphDemand.nearest(ready, 1000).getValue());
        assertEquals("small", FloatingGlyphDemand.nearest(ready, 900).getValue());
        assertEquals("small", FloatingGlyphDemand.nearest(ready, 875).getValue());
        assertNull(FloatingGlyphDemand.nearest(ready, 4000));
        assertNull(FloatingGlyphDemand.nearest(ready, 500));
    }

    @Test
    void refinesRepeatedSizesAndDeduplicatesOutstandingWork() {
        var demand = new FloatingGlyphDemand<String>(8);
        assertEquals(ENQUEUE, demand.plan("cold", true, false, false));
        assertEquals(WAIT, demand.plan("cold", true, true, false));
        assertEquals(WAIT, demand.plan("near", false, false, false));
        assertEquals(WAIT, demand.plan("near", false, false, false));
        assertEquals(WAIT, demand.plan("near", false, false, true));
        assertEquals(ENQUEUE, demand.plan("near", false, false, false));
    }

    @Test
    void unavailableAndFullKeysUseOriginalUntilContextChanges() {
        var demand = new FloatingGlyphDemand<String>(2);
        demand.failed("missing");
        assertEquals(ORIGINAL, demand.plan("missing", true, false, false));
        assertEquals(ENQUEUE, demand.plan("present", true, false, false));
        demand.filled();
        for (int i = 0; i < 10; i++) assertEquals(ORIGINAL, demand.plan("new" + i, false, false, false));
        demand.invalidate();
        assertEquals(ENQUEUE, demand.plan("missing", true, false, false));
        assertEquals(WAIT, demand.plan("new0", false, false, false));
    }

    @Test
    void previewAndRefinementKeepBaselineAndPenAcrossDifferentSourceRects() {
        var base = new GlyphMetrics(-2, 13, -1, 9, 15, 0, 0, 0, 0, 0);
        for (float stroke : new float[]{0f, 1.25f}) {
            var a = FloatingGlyphAtlas.geometry(base, stroke, .125f, .25f, .03f, .05f, 31);
            var b = FloatingGlyphAtlas.geometry(base, stroke, .75f, .5f, .06f, .10f, 53);
            assertEquals(base.xOffset() - stroke, a.xOffset());
            assertEquals(base.bearingY() - stroke, a.bearingY());
            assertEquals(base.width() + 2*stroke, a.width());
            assertEquals(base.height() + 2*stroke, a.height());
            assertEquals(base.xAdvance() + base.xOffset(), a.xOffset() + a.xAdvance());
            assertEquals(a.xOffset(), b.xOffset());
            assertEquals(a.bearingY(), b.bearingY());
            assertEquals(a.width(), b.width());
            assertEquals(a.height(), b.height());
            assertEquals(a.xAdvance(), b.xAdvance());
            assertNotEquals(a.texWidth(), b.texWidth());
            assertNotEquals(a.textureId(), b.textureId());
        }
    }

    @Test
    void packedUploadFlipsRowsAndKeepsTransparentGutter() {
        int[] pixels = {0x33000000,0x77000000,0x55000000,0x11000000};
        var data = FloatingGlyphAtlas.paddedAlpha(new NativeGlyphBitmap(2,2,pixels,0,0,3));
        byte[] actual = new byte[data.remaining()];
        data.get(actual);
        assertArrayEquals(new byte[]{0,0,0,0, 0,0x55,0x11,0, 0,0x33,0x77,0, 0,0,0,0}, actual);
    }
}
