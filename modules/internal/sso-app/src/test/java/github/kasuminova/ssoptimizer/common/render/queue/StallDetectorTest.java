package github.kasuminova.ssoptimizer.common.render.queue;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StallDetectorTest {

    @Test
    void belowThresholdDoesNotTrip() {
        StallDetector detector = new StallDetector(60, 30);
        // 28 个 stall 帧（每帧一次阻塞调用 + swap 推进）不触发熔断信号，
        // 第 29 个 stall 帧仍低于阈值 30
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
        // 单帧内的成批一次性阻塞调用（如战斗初始化时的 shader 编译状态轮询）
        // 只计为 1 个 stall 帧，不触发熔断信号
        StallDetector detector = new StallDetector(4, 3);
        for (int i = 0; i < 10; i++) {
            assertFalse(detector.onStall());
        }
        assertEquals(10, detector.currentWindowStalls());
    }

    @Test
    void slidingWindowExpiresOldStalls() {
        // 窗口 4 帧、阈值 3 个 stall 帧
        StallDetector detector = new StallDetector(4, 3);
        detector.onStall();
        detector.onStall();
        assertEquals(2, detector.currentWindowStalls());
        // 窗口滑过 4 帧，最早一帧的 stall 帧过期
        for (int i = 0; i < 4; i++) {
            detector.onSwap();
        }
        assertEquals(0, detector.currentWindowStalls());
        // 过期后重新累计：2 个 stall 帧不触发，第 3 个触发
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
        // 仍在窗口内，累计 2 次
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
