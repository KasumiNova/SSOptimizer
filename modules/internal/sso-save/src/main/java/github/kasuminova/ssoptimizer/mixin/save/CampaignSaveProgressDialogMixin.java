package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameMixinSignatures.CampaignSaveProgressDialog.TARGET_CLASS)
public abstract class CampaignSaveProgressDialogMixin {
    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.STRING_CONSTRUCTOR,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$captureSaveLabel(final String label,
                                              final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.attachSaveLabel((com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this, label);
    }

    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS_WITH_TEXT,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgressWithText(final String text,
                                                    final float progress,
                                                    final CallbackInfo callbackInfo) {
        if (SaveProgressOverlayCoordinator.isReplayInProgress()) {
            return;
        }

        SaveProgressOverlayCoordinator.reportProgress(
                (com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this,
                text,
                progress
        );
        if (!SaveProgressOverlayCoordinator.hasActiveOpenGlContext()) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgress(final float progress,
                                            final CallbackInfo callbackInfo) {
        if (SaveProgressOverlayCoordinator.isReplayInProgress()) {
            return;
        }

        SaveProgressOverlayCoordinator.reportProgress(
                (com.fs.starfarer.campaign.save.CampaignSaveProgressDialog) (Object) this,
                progress
        );
        if (!SaveProgressOverlayCoordinator.hasActiveOpenGlContext()) {
            callbackInfo.cancel();
        }
    }
}