package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;

public final class CampaignContrailPointCap {
    public static final int CONTRAIL_MAX_POINTS =
            Integer.parseInt(System.getProperty("ssoptimizer.render.contrail.maxpoints", "256"));

    private CampaignContrailPointCap() {
    }

    public static boolean isContrailPointCapReached(final ContrailEngineV2.Contrail contrail,
                                                    final int maxPoints) {
        return maxPoints > 0 && contrail != null && contrail.points.size() >= maxPoints;
    }
}
