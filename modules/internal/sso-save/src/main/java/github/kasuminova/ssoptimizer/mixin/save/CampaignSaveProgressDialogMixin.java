package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.campaign.save.CampaignSaveProgressDialog;
import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameMixinSignatures.CampaignSaveProgressDialog.TARGET_CLASS)
public abstract class CampaignSaveProgressDialogMixin {
    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS_WITH_TEXT,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgressWithText(final String text,
                                                    final float progress,
                                                    final CallbackInfo callbackInfo) {
        final CampaignSaveProgressDialog dialog = (CampaignSaveProgressDialog) (Object) this;
        SaveProgressOverlayCoordinator.reportProgress(dialog, text, progress);
        if (!SaveProgressOverlayCoordinator.canDrawProgress(dialog, false)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = GameMixinSignatures.CampaignSaveProgressDialog.REPORT_PROGRESS,
            at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$reportProgress(final float progress,
                                            final CallbackInfo callbackInfo) {
        final CampaignSaveProgressDialog dialog = (CampaignSaveProgressDialog) (Object) this;
        SaveProgressOverlayCoordinator.reportProgress(dialog, progress);
        if (!SaveProgressOverlayCoordinator.canDrawProgress(dialog, true)) {
            callbackInfo.cancel();
        }
    }
}
