package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import org.lwjgl.util.vector.Vector2f;

import java.util.Map;

public final class CampaignContrailFrame {
    public static final boolean CONTRAIL_LOD_ENABLED =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.render.contrail.lod", "true"));

    public static final float CONTRAIL_LOD_MARGIN =
            Float.parseFloat(System.getProperty("ssoptimizer.render.contrail.lod.margin", "3000"));

    private CampaignContrailFrame() {
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

    public static boolean hasActiveContrails(final ContrailEngineV2 contrails) {
        if (contrails == null) {
            return false;
        }

        final Map<?, ?> activeContrails = contrails.getContrails();
        return activeContrails != null && !activeContrails.isEmpty();
    }
}
