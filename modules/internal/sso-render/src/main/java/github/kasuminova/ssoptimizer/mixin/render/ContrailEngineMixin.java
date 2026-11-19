package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailAdvanceHelper;
import github.kasuminova.ssoptimizer.common.render.engine.ContrailBatchHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

@Mixin(targets = GameClassNames.CONTRAIL_ENGINE_DOTTED)
public abstract class ContrailEngineMixin {

    @Shadow(remap = false, aliases = "groups")
    private Map<?, ?> ssoptimizer$groups;

    @Inject(method = "render(F)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$renderContrails(float alphaScale, CallbackInfo ci) {
        if (ContrailBatchHelper.renderContrails(ssoptimizer$groups, alphaScale)) {
            ci.cancel();
        }
    }

    @Overwrite(remap = false)
    public void advance(float amount) {
        ContrailAdvanceHelper.advance(ssoptimizer$groups, amount);
    }
}
