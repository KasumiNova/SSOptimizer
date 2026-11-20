package ssoptimizer.standalone.mixin;

import com.thoughtworks.xstream.XStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ssoptimizer.standalone.save.SaveFieldBinding;

@Mixin(targets = "com.fs.starfarer.campaign.save.CampaignGameManager", remap = false)
public abstract class CampaignSaveNamesMixin {
    @Inject(method = "getXStream(Ljava/lang/String;)Lcom/thoughtworks/xstream/XStream;", at = @At("RETURN"))
    private static void ssoptimizer$bindSaveNames(CallbackInfoReturnable<XStream> callback) {
        var mapper = callback.getReturnValue().getMapper().lookupMapperOfType(SaveFieldBinding.class);
        ((SaveFieldBinding) mapper).ssoptimizer$enableSaveFields();
    }
}
