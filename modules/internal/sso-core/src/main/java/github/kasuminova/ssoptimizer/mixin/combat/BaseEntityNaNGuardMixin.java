package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.BASE_ENTITY_DOTTED, remap = false)
public abstract class BaseEntityNaNGuardMixin {
    @Inject(method = "setHitpoints", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanHitpointsGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectHitpoints(value, this)) {
            ci.cancel();
        }
    }
}
