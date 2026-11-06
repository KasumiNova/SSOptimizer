package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.junit.jupiter.api.Test;

import java.awt.*;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignFleetMemberViewShiftersTest {
    @Test
    void idleColorShifterSkipsAdvance() {
        final TrackingColorShifter shifter = new TrackingColorShifter(false, Color.WHITE, Color.WHITE);

        CampaignFleetMemberViewShifters.advanceColorShifterIfNeeded(shifter, 1f);

        assertFalse(shifter.advanced);
    }

    @Test
    void shiftedColorShifterStillAdvances() {
        final TrackingColorShifter shifter = new TrackingColorShifter(true, Color.WHITE, Color.WHITE);

        CampaignFleetMemberViewShifters.advanceColorShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
    }

    @Test
    void staleColorShifterStillAdvancesToResetCurrentColor() {
        final TrackingColorShifter shifter = new TrackingColorShifter(false, Color.WHITE, Color.RED);

        CampaignFleetMemberViewShifters.advanceColorShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
    }

    @Test
    void idleValueShifterSkipsAdvance() {
        final TrackingValueShifter shifter = new TrackingValueShifter(false, 5f, 5f);

        CampaignFleetMemberViewShifters.advanceValueShifterIfNeeded(shifter, 1f);

        assertFalse(shifter.advanced);
    }

    @Test
    void staleValueShifterStillAdvancesToResetCurrentValue() {
        final TrackingValueShifter shifter = new TrackingValueShifter(false, 5f, 8f);

        CampaignFleetMemberViewShifters.advanceValueShifterIfNeeded(shifter, 1f);

        assertTrue(shifter.advanced);
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
}
