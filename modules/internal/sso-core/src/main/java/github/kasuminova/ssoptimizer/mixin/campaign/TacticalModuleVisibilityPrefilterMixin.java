package github.kasuminova.ssoptimizer.mixin.campaign;

import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.campaign.fleet.CampaignFleet;
import com.fs.starfarer.settings.StarfarerSettings;
import com.fs.util.container.repo.ObjectRepository;
import github.kasuminova.ssoptimizer.common.campaign.TacticalVisibilityPrefilter;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;

@Mixin(targets = GameMixinSignatures.TacticalModule.TARGET_CLASS, remap = false)
public abstract class TacticalModuleVisibilityPrefilterMixin {
    @Shadow
    private CampaignFleet fleet;

    @Redirect(
            method = GameMixinSignatures.TacticalModule.ADVANCE,
            at = @At(value = "INVOKE", target = GameMixinSignatures.TacticalModule.GET_LIST_TARGET),
            remap = false)
    @SuppressWarnings("unchecked")
    private List<?> ssoptimizer$prefilterScannedFleets(final ObjectRepository objects, final Class<?> type) {
        final List<CampaignFleet> fleets = objects.getList(CampaignFleet.class);
        if (!TacticalVisibilityPrefilter.isEnabled()) {
            return fleets;
        }

        // 玩家舰队在全局传感器关闭时不受距离限制，不能过滤。
        if (this.fleet.isPlayerFleet() && !StarfarerSettings.isCampaignSensorsOn()) {
            return fleets;
        }

        final float viewerX = this.fleet.getLocation().x;
        final float viewerY = this.fleet.getLocation().y;
        final float viewerRadius = this.fleet.getRadius();
        final float sensorRangeMax =
                StarfarerSettings.getSensorRangeMaxForLocation(this.fleet.getContainingLocation());

        final List<CampaignFleet> filtered = new ArrayList<>(fleets.size());
        for (final CampaignFleet target : fleets) {
            // 无传感器 profile 的舰队不受距离限制，不能过滤。
            if (!target.hasSensorProfile()) {
                filtered.add(target);
                continue;
            }
            final float centerDist = Misc.getDistance(
                    viewerX, viewerY, target.getLocation().x, target.getLocation().y);
            if (!TacticalVisibilityPrefilter.isDefinitelyInvisible(
                    centerDist, target.getRadius(), viewerRadius,
                    sensorRangeMax, target.getExtendedDetectedAtRange())) {
                filtered.add(target);
            }
        }
        return filtered;
    }
}
