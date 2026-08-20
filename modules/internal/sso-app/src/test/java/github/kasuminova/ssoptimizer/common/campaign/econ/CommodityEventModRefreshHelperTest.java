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

    private static final class TrackingBridge implements CommodityEventModRefreshBridge {
        private boolean dirty;
        private int     reapplyCount;

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
    }
}