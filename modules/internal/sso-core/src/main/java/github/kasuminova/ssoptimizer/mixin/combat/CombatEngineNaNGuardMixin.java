package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.CombatEngine;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED, remap = false)
public abstract class CombatEngineNaNGuardMixin {
    @Inject(method = "advanceObjects", at = @At("HEAD"))
    private void ssoptimizer$nanMotionGuard(final float amount, final CallbackInfo ci) {
        CombatNaNGuard.checkAllMotion(((CombatEngine) (Object) this).getObjects());
    }
}
