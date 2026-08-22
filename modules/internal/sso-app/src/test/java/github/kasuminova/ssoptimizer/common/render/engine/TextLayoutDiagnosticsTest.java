package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextLayoutDiagnosticsTest {
    @AfterEach
    void tearDown() {
        System.clearProperty(TextLayoutDiagnostics.ENABLE_PROPERTY);
        System.clearProperty(TextLayoutDiagnostics.LOG_INTERVAL_PROPERTY);
        TextLayoutDiagnostics.resetForTests();
    }

    @Test
    void disabledDiagnosticsDoNotCollectV2Stats() {
        TextLayoutDiagnostics.recordV2Render(3, 42, 15.0f);

        assertEquals("", TextLayoutDiagnostics.snapshotSummary());
    }

    @Test
    void enabledDiagnosticsAggregateV2RenderStats() {
        System.setProperty(TextLayoutDiagnostics.ENABLE_PROPERTY, "true");
        System.setProperty(TextLayoutDiagnostics.LOG_INTERVAL_PROPERTY, "0");

        TextLayoutDiagnostics.recordV2Render(3, 42, 15.0f);
        TextLayoutDiagnostics.recordV2Render(1, 10, 15.0f);

        final String summary = TextLayoutDiagnostics.snapshotSummary();
        assertTrue(summary.contains("v2RenderCalls=2"));
        assertTrue(summary.contains("v2Passes=4"));
        assertTrue(summary.contains("v2Quads=52"));
        assertTrue(summary.contains("requestedFontSizes=15.000x2"));
    }
}
