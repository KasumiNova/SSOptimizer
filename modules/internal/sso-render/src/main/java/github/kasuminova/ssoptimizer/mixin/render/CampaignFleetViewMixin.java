package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.campaign.fleet.CampaignFleetView;
import com.fs.starfarer.campaign.fleet.ContrailEngineV2;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignContrailFrame;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CampaignFleetView.class)
public abstract class CampaignFleetViewMixin {
    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetView.CONTRAIL_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$advanceContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                      final float amount) {
        CampaignContrailFrame.advanceContrailsIfNeeded(contrails, amount);
    }

    @Redirect(
            method = "renderContrails",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetView.CONTRAIL_RENDER_TARGET),
            remap = false)
    private void ssoptimizer$renderContrailsIfNeeded(final ContrailEngineV2 contrails,
                                                     final float alphaMult) {
        final CampaignFleetView self = (CampaignFleetView) (Object) this;
        CampaignContrailFrame.renderContrailsIfNeeded(
                contrails, alphaMult,
                self.getFleet().getLocation(),
                Global.getSector().getViewport());
    }
}