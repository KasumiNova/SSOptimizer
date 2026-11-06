package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;

import java.awt.*;
import java.util.Objects;

public final class CampaignFleetMemberViewShifters {
    private CampaignFleetMemberViewShifters() {
    }

    public static void advanceColorShifterIfNeeded(final ColorShifter shifter,
                                                   final float amount) {
        if (shifter == null) {
            return;
        }
        if (!shifter.isShifted() && Objects.equals(currentColor(shifter), shifter.getBase())) {
            return;
        }
        shifter.advance(amount);
    }

    public static void advanceValueShifterIfNeeded(final ValueShifter shifter,
                                                   final float amount) {
        if (shifter == null) {
            return;
        }
        if (!shifter.isShifted() && Float.compare(shifter.getCurr(), shifter.getBase()) == 0) {
            return;
        }
        shifter.advance(amount);
    }

    private static Color currentColor(final ColorShifter shifter) {
        final Color current = shifter.getCurr();
        return current != null ? current : shifter.getBase();
    }
}
