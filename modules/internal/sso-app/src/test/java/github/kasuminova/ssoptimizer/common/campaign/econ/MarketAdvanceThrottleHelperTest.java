package github.kasuminova.ssoptimizer.common.campaign.econ;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketAdvanceThrottleHelperTest {
    private static final float DT = 0.1F;

    @Test
    void newMarketAdvancesImmediately() {
        final TrackingBridge bridge = new TrackingBridge();

        MarketAdvanceThrottleHelper.advanceThrottled(bridge, DT, 5);

        assertEquals(List.of(DT), bridge.advances);
        assertEquals(0.0, bridge.pending);
        assertEquals(1, bridge.callCount);
    }

    @Test
    void intervalOneForwardsEveryFrameUnchanged() {
        final TrackingBridge bridge = new TrackingBridge();

        advanceFrames(bridge, DT, 1, 3);

        assertEquals(List.of(DT, DT, DT), bridge.advances);
        assertEquals(0.0, bridge.pending);
    }

    @Test
    void intervalTwoCadence() {
        final TrackingBridge bridge = new TrackingBridge();

        advanceFrames(bridge, DT, 2, 5);

        assertEquals(3, bridge.advances.size());
        assertEquals(DT, bridge.advances.get(0), 1e-7F);
        assertEquals(DT, bridge.advances.get(1), 1e-7F);
        assertEquals(2.0 * DT, bridge.advances.get(2), 1e-7F);
        assertEquals(DT, bridge.pending, 1e-9);
        assertEquals(5.0 * DT, forwardedTotal(bridge) + bridge.pending, 1e-6);
    }

    @Test
    void intervalFiveCadence() {
        final TrackingBridge bridge = new TrackingBridge();

        advanceFrames(bridge, DT, 5, 11);

        assertEquals(3, bridge.advances.size());
        assertEquals(DT, bridge.advances.get(0), 1e-7F);
        assertEquals(4.0 * DT, bridge.advances.get(1), 1e-6F);
        assertEquals(5.0 * DT, bridge.advances.get(2), 1e-6F);
        assertEquals(DT, bridge.pending, 1e-9);
        assertEquals(11.0 * DT, forwardedTotal(bridge) + bridge.pending, 1e-5);
    }

    @Test
    void doubleAccumulationAvoidsPerFrameFloatDrift() {
        final TrackingBridge bridge = new TrackingBridge();
        final int frames = 100;

        advanceFrames(bridge, DT, frames, frames);

        double doubleSum = 0.0;
        float floatSum = 0.0F;
        for (int i = 1; i < frames; i++) {
            doubleSum += DT;
            floatSum += DT;
        }

        assertEquals(2, bridge.advances.size());
        assertNotEquals(floatSum, bridge.advances.get(1).floatValue());
        assertEquals((float) doubleSum, bridge.advances.get(1).floatValue(), 0.0F);
        assertEquals(0.0, bridge.pending);
    }

    @Test
    void marketsKeepIndependentThrottleState() {
        final TrackingBridge first = new TrackingBridge();
        final TrackingBridge second = new TrackingBridge();

        MarketAdvanceThrottleHelper.advanceThrottled(first, DT, 3);
        MarketAdvanceThrottleHelper.advanceThrottled(first, DT, 3);
        MarketAdvanceThrottleHelper.advanceThrottled(second, DT, 3);

        assertEquals(1, first.advances.size());
        assertEquals(List.of(DT), second.advances);
        assertEquals(DT, first.pending, 1e-9);
    }

    @Test
    void parseIntervalBoundaries() {
        assertEquals(MarketAdvanceThrottleHelper.DEFAULT_INTERVAL,
                MarketAdvanceThrottleHelper.parseInterval(null));
        assertEquals(2, MarketAdvanceThrottleHelper.DEFAULT_INTERVAL);
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("1"));
        assertEquals(3, MarketAdvanceThrottleHelper.parseInterval("3"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("0"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("-7"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("abc"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval(""));
    }

    @Test
    void decideAdvanceSecondsMatchesAdvanceThrottledCadence() {
        for (final int interval : new int[]{1, 2, 3, 5}) {
            final TrackingBridge viaDecide = new TrackingBridge();
            final TrackingBridge viaThrottled = new TrackingBridge();
            for (int frame = 0; frame < 12; frame++) {
                final double dt = MarketAdvanceThrottleHelper.decideAdvanceSeconds(viaDecide, DT, interval);
                if (!Double.isNaN(dt)) {
                    viaDecide.ssoptimizer$advanceNow((float) dt);
                }
                MarketAdvanceThrottleHelper.advanceThrottled(viaThrottled, DT, interval);
            }
            assertEquals(viaThrottled.advances, viaDecide.advances, "interval=" + interval);
            assertEquals(viaThrottled.pending, viaDecide.pending, 1e-9, "interval=" + interval);
        }
    }

    @Test
    void decideAdvanceSecondsReturnsNaNOnMergedFrames() {
        final TrackingBridge bridge = new TrackingBridge();

        assertEquals(DT, MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, DT, 3), 1e-7F);
        assertTrue(Double.isNaN(MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, DT, 3)));
        assertEquals(DT, bridge.pending, 1e-9);
        assertEquals(2.0 * DT, MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, DT, 3), 1e-6F);
        assertEquals(0.0, bridge.pending);
    }

    private static void advanceFrames(final TrackingBridge bridge, final float dt,
                                      final int interval, final int frames) {
        for (int i = 0; i < frames; i++) {
            MarketAdvanceThrottleHelper.advanceThrottled(bridge, dt, interval);
        }
    }

    private static double forwardedTotal(final TrackingBridge bridge) {
        double total = 0.0;
        for (final float advance : bridge.advances) {
            total += advance;
        }
        return total;
    }

    private static final class TrackingBridge implements MarketAdvanceThrottleBridge {
        private final List<Float> advances = new ArrayList<>();
        private double pending;
        private int    callCount;

        @Override
        public double ssoptimizer$getPendingAdvanceSeconds() {
            return pending;
        }

        @Override
        public void ssoptimizer$setPendingAdvanceSeconds(final double seconds) {
            pending = seconds;
        }

        @Override
        public int ssoptimizer$getAdvanceCallCount() {
            return callCount;
        }

        @Override
        public void ssoptimizer$setAdvanceCallCount(final int count) {
            callCount = count;
        }

        @Override
        public void ssoptimizer$advanceNow(final float amount) {
            advances.add(amount);
        }
    }
}
