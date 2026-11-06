package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

/** 监听器回调可能修改原列表，因此迭代必须使用防御性副本。 */
@Mixin(targets = GameMixinSignatures.CampaignEngine.TARGET_CLASS)
public abstract class CampaignEngineBattleReportMixin {

    @Redirect(
            method = GameMixinSignatures.CampaignEngine.REPORT_BATTLE_OCCURRED,
            at = @At(value = "INVOKE",
                     target = GameMixinSignatures.CampaignEngine.GET_EVENT_LISTENERS_TARGET),
            remap = false)
    private List<?> ssoptimizer$copyFleetEventListeners(final CampaignFleetAPI fleet) {
        return new ArrayList<>(fleet.getEventListeners());
    }
}
