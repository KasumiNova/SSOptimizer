package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import org.junit.jupiter.api.Test;
import org.lwjgl.util.vector.Vector2f;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignContrailFrameTest {
    @Test
    void emptyContrailsSkipAdvanceAndRender() {
        final TrackingContrails contrails = new TrackingContrails(Collections.emptyMap());
        final ControlledViewport viewport = new ControlledViewport(true);

        CampaignContrailFrame.advanceContrailsIfNeeded(contrails, 1f);
        CampaignContrailFrame.renderContrailsIfNeeded(contrails, 1f, new Vector2f(500f, 500f), viewport);

        assertFalse(contrails.advanced);
        assertFalse(contrails.rendered);
        assertFalse(CampaignContrailFrame.hasActiveContrails(contrails));
        assertEquals(0, viewport.nearChecks);
    }

    @Test
    void nonEmptyContrailsStillAdvanceAndRender() {
        final TrackingContrails contrails = nonEmptyContrails();
        final ControlledViewport viewport = new ControlledViewport(true);

        CampaignContrailFrame.advanceContrailsIfNeeded(contrails, 1f);
        CampaignContrailFrame.renderContrailsIfNeeded(contrails, 1f, new Vector2f(500f, 500f), viewport);

        assertTrue(contrails.advanced);
        assertTrue(contrails.rendered);
        assertTrue(CampaignContrailFrame.hasActiveContrails(contrails));
    }

    @Test
    void nearViewportFleetContrailsRenderWithConfiguredMargin() {
        final TrackingContrails contrails = nonEmptyContrails();
        final ControlledViewport viewport = new ControlledViewport(true);
        final Vector2f fleetLocation = new Vector2f(500f, 500f);

        CampaignContrailFrame.renderContrailsIfNeeded(contrails, 1f, fleetLocation, viewport);

        assertTrue(contrails.rendered);
        assertEquals(1, viewport.nearChecks);
        assertEquals(fleetLocation, viewport.lastLocation);
        assertEquals(CampaignContrailFrame.CONTRAIL_LOD_MARGIN, viewport.lastMargin);
    }

    @Test
    void farFleetContrailsSkipRender() {
        final TrackingContrails contrails = nonEmptyContrails();
        final ControlledViewport viewport = new ControlledViewport(false);

        CampaignContrailFrame.renderContrailsIfNeeded(
                contrails, 1f, new Vector2f(100000f, 100000f), viewport);

        assertFalse(contrails.rendered);
        assertEquals(1, viewport.nearChecks);
    }

    private static TrackingContrails nonEmptyContrails() {
        final Map<Object, Object> data = new HashMap<>();
        data.put("fleet", new Object());
        return new TrackingContrails(data);
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

    private static final class ControlledViewport implements ViewportAPI {
        private final boolean nearViewport;
        private       int     nearChecks;
        private       Vector2f lastLocation;
        private       float    lastMargin;

        private ControlledViewport(final boolean nearViewport) {
            this.nearViewport = nearViewport;
        }

        @Override
        public boolean isNearViewport(final Vector2f loc, final float margin) {
            nearChecks++;
            lastLocation = loc;
            lastMargin = margin;
            return nearViewport;
        }

        @Override
        public Vector2f getCenter() {
            return new Vector2f();
        }

        @Override
        public float getLLX() {
            return 0f;
        }

        @Override
        public float getLLY() {
            return 0f;
        }

        @Override
        public float getVisibleWidth() {
            return 0f;
        }

        @Override
        public float getVisibleHeight() {
            return 0f;
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
