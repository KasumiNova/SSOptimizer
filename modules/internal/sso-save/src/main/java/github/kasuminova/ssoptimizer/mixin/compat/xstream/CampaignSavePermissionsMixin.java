package github.kasuminova.ssoptimizer.mixin.compat.xstream;

import com.thoughtworks.xstream.XStream;
import github.kasuminova.ssoptimizer.common.save.SavePermissions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "com.fs.starfarer.campaign.save.CampaignGameManager", remap = false)
public abstract class CampaignSavePermissionsMixin {
    @Inject(method = "getXStream(Ljava/lang/String;)Lcom/thoughtworks/xstream/XStream;",
            at = @At("RETURN"), remap = false)
    private static void ssoptimizer$protectSaveTypes(final String version,
                                                     final CallbackInfoReturnable<XStream> cir) {
        SavePermissions.install(cir.getReturnValue());
    }
}
