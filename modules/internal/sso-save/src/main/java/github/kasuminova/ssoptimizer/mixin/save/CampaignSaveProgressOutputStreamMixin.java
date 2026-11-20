package github.kasuminova.ssoptimizer.mixin.save;

import com.fs.starfarer.campaign.save.CampaignSaveProgressDialog;
import github.kasuminova.ssoptimizer.common.save.SaveProgressOverlayCoordinator;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.OutputStream;

@Mixin(targets = GameMixinSignatures.SaveProgressOutputStream.TARGET_CLASS)
public abstract class CampaignSaveProgressOutputStreamMixin {
    @Shadow(remap = false, aliases = GameMixinSignatures.SaveProgressOutputStream.WRITTEN_BYTES_FIELD)
    private volatile long ssoptimizer$writtenBytes;

    @Inject(method = GameMixinSignatures.SaveProgressOutputStream.PROGRESS_CONSTRUCTOR,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$beginSaveProgressPhase(final OutputStream delegate,
                                                    final long expectedBytes,
                                                    final float phaseStart,
                                                    final float phaseEnd,
                                                    final CampaignSaveProgressDialog progressDialog,
                                                    final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.beginStreamPhase(progressDialog, expectedBytes + 10_000L, phaseStart, phaseEnd);
    }

    @Inject(method = GameMixinSignatures.SaveProgressOutputStream.WRITE_BYTES,
            at = @At("RETURN"), remap = false)
    private void ssoptimizer$publishWrittenBytes(final byte[] bytes,
                                                 final int offset,
                                                 final int length,
                                                 final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.onBytesWritten(ssoptimizer$writtenBytes);
    }

    @Inject(method = "updateProgress(Z)V", at = @At(value = "INVOKE",
            target = "Lcom/fs/starfarer/campaign/save/CampaignSaveProgressDialog;reportProgress(Ljava/lang/String;F)V",
            shift = At.Shift.AFTER), remap = false)
    private void ssoptimizer$pumpSaveProgress(final boolean force, final CallbackInfo callbackInfo) {
        SaveProgressOverlayCoordinator.maybePumpFrame();
    }
}