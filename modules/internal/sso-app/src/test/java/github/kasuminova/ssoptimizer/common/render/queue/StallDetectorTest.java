package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallDetectorTest {

    @Test
    void belowThresholdDoesNotTrip() {
        StallDetector detector = new StallDetector(60, 30);
        for (int i = 0; i < 28; i++) {
            assertFalse(detector.onStall());
            detector.onSwap();
        }
        assertFalse(detector.onStall());
        assertEquals(29, detector.currentWindowStalls());
    }

    @Test
    void reachingThresholdTripsWithDiagnostics() {
        StallDetector detector = new StallDetector(60, 30);
        for (int i = 0; i < 29; i++) {
            assertFalse(detector.onStall());
            detector.onSwap();
        }
        assertTrue(detector.onStall(), "第 30 个 stall 帧达到阈值，必须发出熔断信号");
        String diagnostics = detector.tripDiagnostics();
        assertTrue(diagnostics.contains("60"));
        assertTrue(diagnostics.contains("30"));
    }

    @Test
    void singleFrameBurstDoesNotTrip() {
        StallDetector detector = new StallDetector(4, 3);
        for (int i = 0; i < 10; i++) {
            assertFalse(detector.onStall());
        }
        assertEquals(10, detector.currentWindowStalls());
    }

    @Test
    void slidingWindowExpiresOldStalls() {
        StallDetector detector = new StallDetector(4, 3);
        detector.onStall();
        detector.onStall();
        assertEquals(2, detector.currentWindowStalls());
        for (int i = 0; i < 4; i++) {
            detector.onSwap();
        }
        assertEquals(0, detector.currentWindowStalls());
        assertFalse(detector.onStall());
        detector.onSwap();
        assertFalse(detector.onStall());
        detector.onSwap();
        assertTrue(detector.onStall());
    }

    @Test
    void stallsSpreadAcrossFramesWithinWindowStillCount() {
        StallDetector detector = new StallDetector(4, 3);
        detector.onStall();
        detector.onSwap();
        detector.onStall();
        assertEquals(2, detector.currentWindowStalls());
        detector.onSwap();
        assertTrue(detector.onStall());
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new StallDetector(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StallDetector(1, 0));
    }
}
