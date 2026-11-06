package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.common.save.UnmarshalPhaseTimer;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameMixinSignatures.SaveProgressInputStream.TARGET_CLASS)
public abstract class CampaignSaveProgressInputStreamMixin {
    @Inject(method = GameMixinSignatures.SaveProgressInputStream.UPDATE_PROGRESS,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$pumpLoadProgress(final boolean force,
                                              final CallbackInfo callbackInfo) {
        if (force) {
            UnmarshalPhaseTimer.begin();
        }
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }

    @Inject(method = GameMixinSignatures.SaveProgressInputStream.MARK_COMPLETE,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$completeLoadProgress(final CallbackInfo callbackInfo) {
        UnmarshalPhaseTimer.end();
        SaveProgressOverlayCoordinator.complete();
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }
}
