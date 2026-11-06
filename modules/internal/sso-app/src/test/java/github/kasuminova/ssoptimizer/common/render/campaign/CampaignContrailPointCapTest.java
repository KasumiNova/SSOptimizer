package github.kasuminova.ssoptimizer.common.render.campaign;

import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignContrailPointCapTest {
    @Test
    void contrailPointCapTriggersExactlyAtLimit() {
        final ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        final int limit = 4;
        for (int i = 0; i < limit - 1; i++) {
            contrail.points.add(new ContrailEngineV2.ContrailPoint());
        }

        assertFalse(CampaignContrailPointCap.isContrailPointCapReached(contrail, limit));

        contrail.points.add(new ContrailEngineV2.ContrailPoint());
        assertTrue(CampaignContrailPointCap.isContrailPointCapReached(contrail, limit));

        contrail.points.add(new ContrailEngineV2.ContrailPoint());
        assertTrue(CampaignContrailPointCap.isContrailPointCapReached(contrail, limit));
    }

    @Test
    void contrailPointCapDisabledWhenLimitNonPositive() {
        final ContrailEngineV2.Contrail contrail = new ContrailEngineV2.Contrail();
        for (int i = 0; i < 512; i++) {
            contrail.points.add(new ContrailEngineV2.ContrailPoint());
        }

        assertFalse(CampaignContrailPointCap.isContrailPointCapReached(contrail, 0));
        assertFalse(CampaignContrailPointCap.isContrailPointCapReached(contrail, -1));
    }

    @Test
    void unknownContrailSourceIsNotCapped() {
        assertFalse(CampaignContrailPointCap.isContrailPointCapReached(null, 4));
    }
}
