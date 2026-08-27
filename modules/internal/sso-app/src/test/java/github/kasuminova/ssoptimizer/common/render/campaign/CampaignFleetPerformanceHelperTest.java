package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignFleetPerformanceHelperTest {
    @Test
    void idleColorShifterSkipsAdvance() {
        final TrackingColorShifter shifter = new TrackingColorShifter(false, Color.WHITE, Color.WHITE);

        CampaignFleetPerformanceHelper.advanceColorShifterIfNeeded(shifter, 1f);

        assertFalse(shifter.advanced);
    }

    @Test
    void shiftedColorShifterStillAdvances() {
        final TrackingColorShifter shifter = new TrackingColorShifter(true, Color.WHITE, Color.WHITE);

        CampaignFleetPerformanceHelper.advanceColorShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
    }

    @Test
    void staleColorShifterStillAdvancesToResetCurrentColor() {
        final TrackingColorShifter shifter = new TrackingColorShifter(false, Color.WHITE, Color.RED);

        CampaignFleetPerformanceHelper.advanceColorShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
    }

    @Test
    void idleValueShifterSkipsAdvance() {
        final TrackingValueShifter shifter = new TrackingValueShifter(false, 5f, 5f);

        CampaignFleetPerformanceHelper.advanceValueShifterIfNeeded(shifter, 1f);

        assertFalse(shifter.advanced);
    }

    @Test
    void staleValueShifterStillAdvancesToResetCurrentValue() {
        final TrackingValueShifter shifter = new TrackingValueShifter(false, 5f, 8f);

        CampaignFleetPerformanceHelper.advanceValueShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
    }

    @Test
    void emptyContrailsSkipAdvanceAndRender() {
        final TrackingContrails contrails = new TrackingContrails(Collections.emptyMap());
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);

        CampaignFleetPerformanceHelper.advanceContrailsIfNeeded(contrails, 1f);
        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(contrails, 1f, new Vector2f(500f, 500f), viewport);

        assertFalse(contrails.advanced);
        assertFalse(contrails.rendered);
        assertFalse(CampaignFleetPerformanceHelper.hasActiveContrails(contrails));
        // 空集合短路必须先于视口 LOD 判定，不产生任何视口查询
        assertEquals(0, viewport.nearChecks);
    }

    @Test
    void nonEmptyContrailsStillAdvanceAndRender() {
        final TrackingContrails contrails = nonEmptyContrails();
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);

        CampaignFleetPerformanceHelper.advanceContrailsIfNeeded(contrails, 1f);
        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(contrails, 1f, new Vector2f(500f, 500f), viewport);

        assertTrue(contrails.advanced);
        assertTrue(contrails.rendered);
        assertTrue(CampaignFleetPerformanceHelper.hasActiveContrails(contrails));
    }

    @Test
    void nearViewportFleetContrailsRenderWithConfiguredMargin() {
        final TrackingContrails contrails = nonEmptyContrails();
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        final Vector2f fleetLocation = new Vector2f(500f, 500f);

        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(contrails, 1f, fleetLocation, viewport);

        assertTrue(contrails.rendered);
        // LOD 判定必须把配置的边距原样传给视口查询
        assertEquals(1, viewport.nearChecks);
        assertEquals(fleetLocation, viewport.lastLocation);
        assertEquals(CampaignFleetPerformanceHelper.CONTRAIL_LOD_MARGIN, viewport.lastMargin);
    }

    @Test
    void farFleetContrailsSkipRender() {
        final TrackingContrails contrails = nonEmptyContrails();
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);

        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(
                contrails, 1f, new Vector2f(100000f, 100000f), viewport);

        assertFalse(contrails.rendered);
        assertEquals(1, viewport.nearChecks);
    }

    @Test
    void fleetJustInsideLodMarginStillRenders() {
        final TrackingContrails contrails = nonEmptyContrails();
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        final float margin = CampaignFleetPerformanceHelper.CONTRAIL_LOD_MARGIN;

        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(
                contrails, 1f, new Vector2f(1000f + margin - 1f, 500f), viewport);

        assertTrue(contrails.rendered);
    }

    @Test
    void fleetJustBeyondLodMarginSkipsRender() {
        final TrackingContrails contrails = nonEmptyContrails();
        final RectViewport viewport = new RectViewport(0f, 0f, 1000f, 1000f);
        final float margin = CampaignFleetPerformanceHelper.CONTRAIL_LOD_MARGIN;

        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(
                contrails, 1f, new Vector2f(1000f + margin + 1f, 500f), viewport);

        assertFalse(contrails.rendered);
    }

    @Test
    void contrailPointCapTriggersExactlyAtLimit() {
        final ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        final int limit = 4;
        for (int i = 0; i < limit - 1; i++) {
            contrail.points.add(new ContrailEngineV2.ContrailPoint());
        }

        assertFalse(CampaignFleetPerformanceHelper.isContrailPointCapReached(contrail, limit));

        contrail.points.add(new ContrailEngineV2.ContrailPoint());
        assertTrue(CampaignFleetPerformanceHelper.isContrailPointCapReached(contrail, limit));

        contrail.points.add(new ContrailEngineV2.ContrailPoint());
        assertTrue(CampaignFleetPerformanceHelper.isContrailPointCapReached(contrail, limit));
    }

    @Test
    void contrailPointCapDisabledWhenLimitNonPositive() {
        final ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        for (int i = 0; i < 512; i++) {
            contrail.points.add(new ContrailEngineV2.ContrailPoint());
        }

        assertFalse(CampaignFleetPerformanceHelper.isContrailPointCapReached(contrail, 0));
        assertFalse(CampaignFleetPerformanceHelper.isContrailPointCapReached(contrail, -1));
    }

    @Test
    void unknownContrailSourceIsNotCapped() {
        assertFalse(CampaignFleetPerformanceHelper.isContrailPointCapReached(null, 4));
    }

    @Test
    void defaultPointCapDoesNotInterfereWithSteadyStateTrails() {
        // 稳态尾迹点数（duration ≤2s × 补点速率）为百级，默认上限 256 不应拦截
        final ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        for (int i = 0; i < 120; i++) {
            contrail.points.add(new ContrailEngineV2.ContrailPoint());
        }

        assertFalse(CampaignFleetPerformanceHelper.isContrailPointCapReached(
                contrail, CampaignFleetPerformanceHelper.CONTRAIL_MAX_POINTS));
    }

    private static TrackingContrails nonEmptyContrails() {
        final Map<Object, Object> data = new HashMap<>();
        data.put("fleet", new Object());
        return new TrackingContrails(data);
    }

    private static final class TrackingColorShifter extends ColorShifter {
        private final boolean shifted;
        private final Color   base;
        private final Color   curr;
        private       boolean advanced;

        private TrackingColorShifter(final boolean shifted,
                                     final Color base,
                                     final Color curr) {
            super(base);
            this.shifted = shifted;
            this.base = base;
            this.curr = curr;
        }

        @Override
        public boolean isShifted() {
            return shifted;
        }

        @Override
        public Color getBase() {
            return base;
        }

        @Override
        public Color getCurr() {
            return curr;
        }

        @Override
        public void advance(final float amount) {
            advanced = true;
        }
    }

    private static final class TrackingValueShifter extends ValueShifter {
        private final boolean shifted;
        private final float   base;
        private final float   curr;
        private       boolean advanced;

        private TrackingValueShifter(final boolean shifted,
                                     final float base,
                                     final float curr) {
            super(base);
            this.shifted = shifted;
            this.base = base;
            this.curr = curr;
        }

        @Override
        public boolean isShifted() {
            return shifted;
        }

        @Override
        public float getBase() {
            return base;
        }

        @Override
        public float getCurr() {
            return curr;
        }

        @Override
        public void advance(final float amount) {
            advanced = true;
        }
    }

    private static final class TrackingContrails extends ContrailEngineV2 {
        private final Map<?, ?> active;
        private       boolean   advanced;
        private       boolean   rendered;

        private TrackingContrails(final Map<?, ?> active) {
            this.active = active;
        }

        @Override
        public Map getContrails() {
            return active;
        }

        @Override
        public void advance(final float amount) {
            advanced = true;
        }

        @Override
        public void render(final float alpha) {
            rendered = true;
        }
    }

    /**
     * 矩形视口 stub：按「可视矩形 ± margin」实现 isNearViewport 语义，
     * 并记录每次查询的位置与边距以验证 helper 的参数透传。
     */
    private static final class RectViewport implements ViewportAPI {
        private final float llx;
        private final float lly;
        private final float width;
        private final float height;
        private       int   nearChecks;
        private       Vector2f lastLocation;
        private       float    lastMargin;

        private RectViewport(final float llx, final float lly, final float width, final float height) {
            this.llx = llx;
            this.lly = lly;
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean isNearViewport(final Vector2f loc, final float margin) {
            nearChecks++;
            lastLocation = loc;
            lastMargin = margin;
            return loc.x >= llx - margin && loc.x <= llx + width + margin
                    && loc.y >= lly - margin && loc.y <= lly + height + margin;
        }

        @Override
        public Vector2f getCenter() {
            return new Vector2f(llx + width / 2f, lly + height / 2f);
        }

        @Override
        public float getLLX() {
            return llx;
        }

        @Override
        public float getLLY() {
            return lly;
        }

        @Override
        public float getVisibleWidth() {
            return width;
        }

        @Override
        public float getVisibleHeight() {
            return height;
        }

        @Override
        public float getWorldXtoScreenX() {
            return 1f;
        }

        @Override
        public float getWorldYtoScreenY() {
            return 1f;
        }

        @Override
        public float getViewMult() {
            return 1f;
        }

        @Override
        public float getAlphaMult() {
            return 1f;
        }

        @Override
        public float convertScreenXToWorldX(final float x) {
            return x;
        }

        @Override
        public float convertScreenYToWorldY(final float y) {
            return y;
        }

        @Override
        public float convertWorldXtoScreenX(final float x) {
            return x;
        }

        @Override
        public float convertWorldYtoScreenY(final float y) {
            return y;
        }

        @Override
        public float convertWorldWidthToScreenWidth(final float w) {
            return w;
        }

        @Override
        public float convertWorldHeightToScreenHeight(final float h) {
            return h;
        }

        @Override
        public float convertScreenWidthToWorldWidth(final float w) {
            return w;
        }

        @Override
        public float convertScreenHeightToWorldHeight(final float h) {
            return h;
        }

        @Override
        public void set(final float llx, final float lly, final float width, final float height) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setViewMult(final float viewMult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExternalControl() {
            return false;
        }

        @Override
        public void setExternalControl(final boolean externalControl) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setCenter(final Vector2f center) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAlphaMult(final float alphaMult) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEverythingNearViewport() {
            return false;
        }

        @Override
        public void setEverythingNearViewport(final boolean everythingNearViewport) {
            throw new UnsupportedOperationException();
        }
    }
}
