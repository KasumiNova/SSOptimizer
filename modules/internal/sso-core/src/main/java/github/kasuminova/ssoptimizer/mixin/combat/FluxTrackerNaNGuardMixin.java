package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.FLUX_TRACKER_DOTTED, remap = false)
public abstract class FluxTrackerNaNGuardMixin {
    @Shadow
    private Ship ship;

    @Inject(method = "increaseFlux(FZZZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanFluxGuard(final float amount, final boolean forceOverload,
                                          final boolean noOverload, final boolean hardFlux,
                                          final boolean noHardFluxClamp,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if (CombatNaNGuard.shouldRejectFlux(amount, this, ship)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setCurrFlux(F)V", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanCurrFluxGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectFlux(value, this, ship)) {
            ci.cancel();
        }
    }

    @Inject(method = "setMinFlux(F)V", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanMinFluxGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectFlux(value, this, ship)) {
            ci.cancel();
        }
    }
}
