package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.junit.jupiter.api.Test;

import java.awt.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

        CampaignFleetPerformanceHelper.advanceContrailsIfNeeded(contrails, 1f);
        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(contrails, 1f);

        assertFalse(contrails.advanced);
        assertFalse(contrails.rendered);
        assertFalse(CampaignFleetPerformanceHelper.hasActiveContrails(contrails));
    }

    @Test
    void nonEmptyContrailsStillAdvanceAndRender() {
        final Map<Object, Object> data = new HashMap<>();
        data.put("fleet", new Object());
        final TrackingContrails contrails = new TrackingContrails(data);

        CampaignFleetPerformanceHelper.advanceContrailsIfNeeded(contrails, 1f);
        CampaignFleetPerformanceHelper.renderContrailsIfNeeded(contrails, 1f);

        assertTrue(contrails.advanced);
        assertTrue(contrails.rendered);
        assertTrue(CampaignFleetPerformanceHelper.hasActiveContrails(contrails));
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
}