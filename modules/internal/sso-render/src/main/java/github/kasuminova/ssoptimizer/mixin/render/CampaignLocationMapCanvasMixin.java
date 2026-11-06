package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.campaign.CampaignMapEntityRetention;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.Set;

@Mixin(targets = GameMixinSignatures.CampaignLocationMapCanvas.TARGET_CLASS)
public abstract class CampaignLocationMapCanvasMixin {
    @Redirect(
            method = GameMixinSignatures.CampaignLocationMapCanvas.RENDER_STUFF,
            at = @At(value = "INVOKE", target = GameMixinSignatures.CampaignLocationMapCanvas.SET_RETAIN_ALL_TARGET),
            remap = false)
    private boolean ssoptimizer$retainLiveEntitiesWithLookupSet(final Set<?> targetSet,
                                                                final Collection<?> liveEntities) {
        return CampaignMapEntityRetention.retainLiveEntities(targetSet, liveEntities);
    }
}