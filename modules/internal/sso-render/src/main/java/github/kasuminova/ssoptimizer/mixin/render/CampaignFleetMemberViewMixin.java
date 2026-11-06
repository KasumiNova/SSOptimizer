package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.campaign.fleet.CampaignFleetMemberView;
import com.fs.starfarer.util.ColorShifter;
import com.fs.starfarer.util.ValueShifter;
import github.kasuminova.ssoptimizer.common.render.campaign.CampaignFleetPerformanceHelper;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(CampaignFleetMemberView.class)
public abstract class CampaignFleetMemberViewMixin {
    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetMemberView.COLOR_SHIFTER_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$advanceColorShifterIfNeeded(final ColorShifter shifter,
                                                         final float amount) {
        CampaignFleetPerformanceHelper.advanceColorShifterIfNeeded(shifter, amount);
    }

    @Redirect(
            method = "advance",
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignFleetMemberView.VALUE_SHIFTER_ADVANCE_TARGET),
            remap = false)
    private void ssoptimizer$advanceValueShifterIfNeeded(final ValueShifter shifter,
                                                         final float amount) {
        CampaignFleetPerformanceHelper.advanceValueShifterIfNeeded(shifter, amount);
    }
}