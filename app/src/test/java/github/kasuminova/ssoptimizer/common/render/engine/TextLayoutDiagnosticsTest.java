package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLayoutDiagnosticsTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TextRenderDiagnostics.ENABLE_PROPERTY);
        System.clearProperty(TextRenderDiagnostics.LOG_INTERVAL_PROPERTY);
        TextLayoutDiagnostics.resetForTests();
    }

    @Test
    void disabledDiagnosticsDoNotCollectLayoutStats() {
        TextLayoutDiagnostics.recordGlyphLayout(65, 1, 7, 1001, 1.0f, 15.0f, 15, 19);

        assertEquals("", TextLayoutDiagnostics.snapshotSummary());
    }

    @Test
    void enabledDiagnosticsAggregateScaleAwareCacheHints() {
        System.setProperty(TextRenderDiagnostics.ENABLE_PROPERTY, "true");
        System.setProperty(TextRenderDiagnostics.LOG_INTERVAL_PROPERTY, "0");

        TextLayoutDiagnostics.recordGlyphLayout(65, 1, 7, 1001, 1.25f, 18.75f, 15, 19);
        TextLayoutDiagnostics.recordGlyphLayout(65, 1, 7, 1001, 1.24f, 18.75f, 15, 19);
        TextLayoutDiagnostics.recordGlyphLayout(66, -1, 8, 2002, 1.0f, 21.0f, 21, 25);

        final String summary = TextLayoutDiagnostics.snapshotSummary();
        assertTrue(summary.contains("layoutGlyphs=3"));
        assertTrue(summary.contains("uniqueFontInstances=2"));
        assertTrue(summary.contains("scaleBuckets=1.250x2, 1.000x1"));
        assertTrue(summary.contains("requestedFontSizes=18.750x2, 21.000x1"));
        assertTrue(summary.contains("nominalFontSizes=15x2, 21x1"));
        assertTrue(summary.contains("topCacheKeys=f3e9/n15/lh19/s1250/g65=2"));
        assertTrue(summary.contains("topXAdvances=7x2, 8x1"));
        assertTrue(summary.contains("topXOffsets=1x2, -1x1"));
    }
}