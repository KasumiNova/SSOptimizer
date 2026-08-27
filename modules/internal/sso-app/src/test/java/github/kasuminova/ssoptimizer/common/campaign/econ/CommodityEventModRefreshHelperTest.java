package github.kasuminova.ssoptimizer.common.campaign.econ;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommodityEventModRefreshHelperTest {
    @Test
    void markDirtySetsDirtyFlag() {
        final TrackingBridge bridge = new TrackingBridge();

        CommodityEventModRefreshHelper.markDirty(bridge);

        assertTrue(bridge.dirty);
        assertEquals(0, bridge.reapplyCount);
    }

    @Test
    void ensureFreshSkipsWhenNotDirty() {
        final TrackingBridge bridge = new TrackingBridge();

        CommodityEventModRefreshHelper.ensureFreshIfDirty(bridge);

        assertEquals(0, bridge.reapplyCount);
        assertFalse(bridge.dirty);
    }

    @Test
    void ensureFreshReappliesAndClearsDirtyFlag() {
        final TrackingBridge bridge = new TrackingBridge();
        bridge.dirty = true;

        CommodityEventModRefreshHelper.ensureFreshIfDirty(bridge);

        assertEquals(1, bridge.reapplyCount);
        assertFalse(bridge.dirty);
    }

    @Test
    void signatureChangedOnFirstObservation() {
        assertTrue(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                false, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void signatureUnchangedWhenAllGenerationsMatch() {
        assertFalse(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                true, 1, 2, 3, 4, 1, 2, 3, 4));
    }

    @Test
    void signatureChangedWhenAnyGenerationDrifts() {
        // 四个分量逐一验证：任一代际变化都必须被检出
        assertTrue(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                true, 1, 2, 3, 4, 9, 2, 3, 4));
        assertTrue(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                true, 1, 2, 3, 4, 1, 9, 3, 4));
        assertTrue(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                true, 1, 2, 3, 4, 1, 2, 9, 4));
        assertTrue(CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                true, 1, 2, 3, 4, 1, 2, 3, 9));
    }

    @Test
    void signatureCheckMarksDirtyAndRecordsOnFirstObservation() {
        final TrackingBridge bridge = new TrackingBridge();

        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);

        assertTrue(bridge.dirty);
        assertTrue(bridge.initialized);
        assertEquals(10, bridge.sigAvailableGen);
        assertEquals(40, bridge.sigTradeModMinusGen);
    }

    @Test
    void signatureCheckStaysQuietWhenSignatureUnchanged() {
        final TrackingBridge bridge = new TrackingBridge();
        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);
        bridge.dirty = false;

        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);

        assertFalse(bridge.dirty);
    }

    @Test
    void signatureCheckKeepsWritePathDirtyFlagWhenSignatureUnchanged() {
        // addTradeMod* 写路径置脏后，推进检测不得因签名未变而清除该脏标记
        final TrackingBridge bridge = new TrackingBridge();
        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);
        bridge.dirty = true;

        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);

        assertTrue(bridge.dirty);
    }

    @Test
    void signatureCheckMarksDirtyWhenGenerationDrifts() {
        final TrackingBridge bridge = new TrackingBridge();
        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 10, 20, 30, 40);
        bridge.dirty = false;

        CommodityEventModRefreshHelper.markDirtyIfSignatureChanged(bridge, 11, 20, 30, 40);

        assertTrue(bridge.dirty);
        assertEquals(11, bridge.sigAvailableGen);
    }

    @Test
    void skipReapplyOnlyWhenQuantityZeroAndNoEventMod() {
        assertTrue(CommodityEventModRefreshHelper.shouldSkipReapply(0.0F, false));
        assertFalse(CommodityEventModRefreshHelper.shouldSkipReapply(0.0F, true));
        assertFalse(CommodityEventModRefreshHelper.shouldSkipReapply(5.0F, false));
        assertFalse(CommodityEventModRefreshHelper.shouldSkipReapply(-3.0F, false));
    }

    /**
     * 桥接测试桩：签名比较走真实 helper 纯函数，存储字段与 Mixin 实现同构。
     */
    private static final class TrackingBridge implements CommodityEventModRefreshBridge {
        private boolean dirty;
        private int     reapplyCount;
        private boolean initialized;
        private int     sigAvailableGen;
        private int     sigTradeModGen;
        private int     sigTradeModPlusGen;
        private int     sigTradeModMinusGen;

        @Override
        public boolean ssoptimizer$isEventModDirty() {
            return dirty;
        }

        @Override
        public void ssoptimizer$setEventModDirty(final boolean dirty) {
            this.dirty = dirty;
        }

        @Override
        public void ssoptimizer$reapplyEventModNow() {
            reapplyCount++;
        }

        @Override
        public boolean ssoptimizer$updateTradeModSignatureAndCheckChanged(
                final int availableGen, final int tradeModGen,
                final int tradeModPlusGen, final int tradeModMinusGen) {
            final boolean changed = CommodityEventModRefreshHelper.isTradeModSignatureChanged(
                    initialized, sigAvailableGen, sigTradeModGen, sigTradeModPlusGen, sigTradeModMinusGen,
                    availableGen, tradeModGen, tradeModPlusGen, tradeModMinusGen);

            initialized = true;
            sigAvailableGen = availableGen;
            sigTradeModGen = tradeModGen;
            sigTradeModPlusGen = tradeModPlusGen;
            sigTradeModMinusGen = tradeModMinusGen;
            return changed;
        }
    }
}
