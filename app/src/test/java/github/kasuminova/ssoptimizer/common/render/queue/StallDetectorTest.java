package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallDetectorTest {

    @Test
    void belowThresholdDoesNotThrow() {
        StallDetector detector = new StallDetector(60, 30);
        for (int i = 0; i < 29; i++) {
            assertDoesNotThrow(detector::onStall);
        }
        assertEquals(29, detector.currentWindowStalls());
    }

    @Test
    void reachingThresholdThrowsWithDiagnostics() {
        StallDetector detector = new StallDetector(60, 30);
        for (int i = 0; i < 29; i++) {
            detector.onStall();
        }
        IllegalStateException ex = assertThrows(IllegalStateException.class, detector::onStall);
        String message = String.valueOf(ex.getMessage());
        assertTrue(message.contains("60"));
        assertTrue(message.contains("30"));
    }

    @Test
    void slidingWindowExpiresOldStalls() {
        // 窗口 4 帧、阈值 3 次
        StallDetector detector = new StallDetector(4, 3);
        detector.onStall();
        detector.onStall();
        assertEquals(2, detector.currentWindowStalls());
        // 窗口滑过 4 帧，最早一帧的 2 次 stall 过期
        for (int i = 0; i < 4; i++) {
            detector.onSwap();
        }
        assertEquals(0, detector.currentWindowStalls());
        // 过期后重新累计：2 次不抛，第 3 次抛
        detector.onStall();
        assertDoesNotThrow(detector::onStall);
        assertThrows(IllegalStateException.class, detector::onStall);
    }

    @Test
    void stallsSpreadAcrossFramesWithinWindowStillCount() {
        StallDetector detector = new StallDetector(4, 3);
        detector.onStall();
        detector.onSwap();
        detector.onStall();
        // 仍在窗口内，累计 2 次
        assertEquals(2, detector.currentWindowStalls());
        detector.onSwap();
        assertThrows(IllegalStateException.class, detector::onStall);
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> new StallDetector(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new StallDetector(1, 0));
    }
}
