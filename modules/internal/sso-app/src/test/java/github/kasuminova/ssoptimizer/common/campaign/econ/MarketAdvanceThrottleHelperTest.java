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
        // 即使降频间隔为 5，新市场首次出现也必须立即获得一次真实推进
        final TrackingBridge bridge = new TrackingBridge();

        MarketAdvanceThrottleHelper.advanceThrottled(bridge, DT, 5);

        assertEquals(List.of(DT), bridge.advances);
        assertEquals(0.0, bridge.pending);
        assertEquals(1, bridge.callCount);
    }

    @Test
    void intervalOneForwardsEveryFrameUnchanged() {
        // interval=1 等价关闭降频：逐帧原样转发
        final TrackingBridge bridge = new TrackingBridge();

        advanceFrames(bridge, DT, 1, 3);

        assertEquals(List.of(DT, DT, DT), bridge.advances);
        assertEquals(0.0, bridge.pending);
    }

    @Test
    void intervalTwoCadence() {
        // N=2：第 1 帧立即推进，第 2 帧推进，之后每 2 帧以累计 dt 转发
        final TrackingBridge bridge = new TrackingBridge();

        advanceFrames(bridge, DT, 2, 5);

        assertEquals(3, bridge.advances.size());
        assertEquals(DT, bridge.advances.get(0), 1e-7F);
        assertEquals(DT, bridge.advances.get(1), 1e-7F);
        assertEquals(2.0 * DT, bridge.advances.get(2), 1e-7F);
        // 第 5 帧被合并挂起
        assertEquals(DT, bridge.pending, 1e-9);
        // 已转发 + 待推进 == 总请求 dt
        assertEquals(5.0 * DT, forwardedTotal(bridge) + bridge.pending, 1e-6);
    }

    @Test
    void intervalFiveCadence() {
        // N=5：第 1 帧立即推进，第 5、10 帧各以累计 dt 转发
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
        // 100 帧 dt=0.1f：helper 以 double 累计，结果必须与 double 求和完全一致，
        // 且与逐帧 float 累加的漂移结果不同
        final TrackingBridge bridge = new TrackingBridge();
        final int frames = 100;

        advanceFrames(bridge, DT, frames, frames);

        double doubleSum = 0.0;
        float floatSum = 0.0F;
        for (int i = 1; i < frames; i++) {
            doubleSum += DT;
            floatSum += DT;
        }

        // 第 1 帧立即推进 DT，第 100 帧转发其余 99 帧的累计
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
        // second 是新市场：即便 first 已累计两帧，second 仍立即推进
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
        // 非法值一律按 1（逐帧转发）处理
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("0"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("-7"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval("abc"));
        assertEquals(1, MarketAdvanceThrottleHelper.parseInterval(""));
    }

    @Test
    void decideAdvanceSecondsMatchesAdvanceThrottledCadence() {
        // decide 与 advanceThrottled 共用同一判定：非 NaN 返回序列必须与
        // advanceThrottled 的推进序列完全一致，NaN 帧不推进
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

        // 第 1 帧：新市场立即推进（非 NaN）
        assertEquals(DT, MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, DT, 3), 1e-7F);
        // 第 2 帧：被合并 → NaN，dt 挂起到 pending
        assertTrue(Double.isNaN(MarketAdvanceThrottleHelper.decideAdvanceSeconds(bridge, DT, 3)));
        assertEquals(DT, bridge.pending, 1e-9);
        // 第 3 帧：以累计 dt 转发
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

    /**
     * 桥接测试桩：状态字段与 Mixin 注入字段同构，记录每次真实推进的 dt。
     */
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
