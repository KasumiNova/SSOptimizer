package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.CHARACTER_STATS_DOTTED, remap = false)
public abstract class CharacterStatsReadResolveBatchMixin {
    @Shadow
    private boolean skipRefresh;

    @Shadow
    public abstract void refreshCharacterStatsEffects();

    @Inject(method = "readResolve", at = @At("HEAD"), remap = false)
    private void ssoptimizer$batchRefreshBegin(final CallbackInfoReturnable<Object> ci) {
        skipRefresh = true;
    }

    @Inject(method = "readResolve", at = @At("RETURN"), remap = false)
    private void ssoptimizer$batchRefreshEnd(final CallbackInfoReturnable<Object> ci) {
        skipRefresh = false;
        refreshCharacterStatsEffects();
    }
}
