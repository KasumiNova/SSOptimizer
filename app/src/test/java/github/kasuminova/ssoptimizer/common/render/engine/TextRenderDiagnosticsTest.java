package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextRenderDiagnosticsTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TextRenderDiagnostics.ENABLE_PROPERTY);
        System.clearProperty(TextRenderDiagnostics.LOG_INTERVAL_PROPERTY);
        TextRenderDiagnostics.resetForTests();
    }

    @Test
    void disabledDiagnosticsDoNotCollectStats() {
        TextRenderDiagnostics.recordGlyphQuad(10, 14, 9, 0.015625f, 0.03125f, 1.0f, 0, true);

        assertEquals("", TextRenderDiagnostics.snapshotSummary());
    }

    @Test
    void enabledDiagnosticsAggregateGlyphUsage() {
        System.setProperty(TextRenderDiagnostics.ENABLE_PROPERTY, "true");
        System.setProperty(TextRenderDiagnostics.LOG_INTERVAL_PROPERTY, "0");

        TextRenderDiagnostics.recordGlyphQuad(10, 14, 9, 0.015625f, 0.03125f, 1.0f, 0, true);
        TextRenderDiagnostics.recordGlyphQuad(10, 14, 9, 0.015625f, 0.03125f, 1.25f, 2, false);
        TextRenderDiagnostics.recordGlyphQuad(6, 7, 5, 0.0078125f, 0.015625f, 1.25f, 1, true);

        final String summary = TextRenderDiagnostics.snapshotSummary();
        assertTrue(summary.contains("glyphQuads=3"));
        assertTrue(summary.contains("native=2"));
        assertTrue(summary.contains("java=1"));
        assertTrue(summary.contains("shadowPasses=3"));
        assertTrue(summary.contains("scaledGlyphs=2"));
        assertTrue(summary.contains("microGlyphs=1"));
        assertTrue(summary.contains("maxShadowCopies=2"));
        assertTrue(summary.contains("scaleBuckets=1.250x2, 1.000x1"));
        assertTrue(summary.contains("10x14/b9/uv156x313/s1250/sh2=1"));
    }
}