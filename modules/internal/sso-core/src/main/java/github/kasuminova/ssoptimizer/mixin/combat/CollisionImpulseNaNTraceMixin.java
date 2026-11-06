package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.CollisionEntity;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = GameClassNames.COLLISION_HANDLER_IMPL_DOTTED, remap = false)
public abstract class CollisionImpulseNaNTraceMixin {
    @Inject(method = "applyCollisionImpulse", at = @At("HEAD"))
    private static void ssoptimizer$nanCollisionTrace(final CollisionEntity a, final CollisionEntity b,
                                                      final Vector2f point,
                                                      final CallbackInfoReturnable<Float> cir) {
        CombatNaNGuard.traceCollisionImpulse(a, b, point);
    }
}
