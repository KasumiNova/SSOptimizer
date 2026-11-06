package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.engine.ContrailSegmentStore;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(targets = GameClassNames.CONTRAIL_GROUP_DOTTED)
public abstract class ContrailGroupMixin {

    @SuppressWarnings("rawtypes")
    @Shadow(remap = false)
    private List segments;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void ssoptimizer$replaceLinkedListWithArrayStore(CallbackInfo ci) {
        this.segments = new ContrailSegmentStore();
    }
}
