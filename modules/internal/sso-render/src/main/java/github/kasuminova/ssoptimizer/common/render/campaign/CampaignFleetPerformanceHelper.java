package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import org.lwjgl.util.vector.Vector2f;

import java.awt.*;
import java.util.Map;
import java.util.Objects;

public final class CampaignFleetPerformanceHelper {
    public static final boolean CONTRAIL_LOD_ENABLED =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.contrail.lod", "true"));

    public static final float CONTRAIL_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.contrail.lod.margin", "3000"));

    public static final int CONTRAIL_MAX_POINTS =
            Integer.parseInt(System.getProperty("ssoptimizer.render.contrail.maxpoints", "256"));

    private CampaignFleetPerformanceHelper() {
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

    public static void advanceContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                final float amount) {
        if (!hasActiveContrails(contrails)) {
            return;
        }
        contrails.advance(amount);
    }

    public static void renderContrailsIfNeeded(final ContrailEngineV2 contrails,
                                               final float alphaMult,
                                               final Vector2f fleetLocation,
                                               final ViewportAPI viewport) {
        if (!hasActiveContrails(contrails)) {
            return;
        }
        if (CONTRAIL_LOD_ENABLED && !viewport.isNearViewport(fleetLocation, CONTRAIL_LOD_MARGIN)) {
            return;
        }
        contrails.render(alphaMult);
    }

    public static boolean isContrailPointCapReached(final ContrailEngineV2.Contrail contrail,
                                                    final int maxPoints) {
        return maxPoints > 0 && contrail != null && contrail.points.size() >= maxPoints;
    }

    public static boolean hasActiveContrails(final ContrailEngineV2 contrails) {
        if (contrails == null) {
            return false;
        }

        final Map<?, ?> activeContrails = contrails.getContrails();
        return activeContrails != null && !activeContrails.isEmpty();
    }

    private static Color currentColor(final ColorShifter shifter) {
        final Color current = shifter.getCurr();
        return current != null ? current : shifter.getBase();
    }
}
