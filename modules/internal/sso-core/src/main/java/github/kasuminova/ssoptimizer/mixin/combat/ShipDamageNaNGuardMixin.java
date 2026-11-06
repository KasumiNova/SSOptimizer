package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.entities.ship.ApplyDamageResult;
import com.fs.starfarer.combat.Damage;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.SHIP_DOTTED, remap = false)
public abstract class ShipDamageNaNGuardMixin {
    @Inject(method = "applyDamageInner(Lorg/lwjgl/util/vector/Vector2f;Lcom/fs/starfarer/combat/Damage;ZZFLjava/lang/Object;)Lcom/fs/starfarer/combat/entities/ship/ApplyDamageResult;",
            at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanDamageGuard(final Vector2f point, final Damage damage,
                                            final boolean shieldHit, final boolean bypassShields,
                                            final float damageMult, final Object source,
                                            final CallbackInfoReturnable<ApplyDamageResult> cir) {
        if (CombatNaNGuard.shouldDiscardDamage(damage, damageMult, source, this)) {
            cir.setReturnValue(new ApplyDamageResult());
        }
    }
}
