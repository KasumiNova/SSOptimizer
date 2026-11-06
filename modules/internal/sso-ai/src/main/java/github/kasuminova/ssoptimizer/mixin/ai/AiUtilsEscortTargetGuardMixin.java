package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.starfarer.combat.CollisionEntity;
import com.fs.starfarer.combat.ai.movement.maneuvers.EscortTargetManeuverV3;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 为递归护航目标判断增加空参数与并发机动读取守卫。
 * {@code instanceof} 后再次读取机动可能得到 {@code null}，返回 {@code null} 使后续递归按非护航目标处理。
 */
@Mixin(targets = GameClassNames.AI_UTILS_DOTTED)
public abstract class AiUtilsEscortTargetGuardMixin {
    @Inject(
            method = "isEscortTargetOf(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void ssoptimizer$guardNullEscortArgs(final Ship arg, final Ship arg2, final int depth,
                                                        final CallbackInfoReturnable<Boolean> cir) {
        if (arg == null || arg2 == null) {
            cir.setReturnValue(false);
        }
    }

    @Redirect(
            method = "isEscortTargetOf(Lcom/fs/starfarer/combat/entities/Ship;Lcom/fs/starfarer/combat/entities/Ship;I)Z",
            at = @At(value = "INVOKE",
                    target = "Lcom/fs/starfarer/combat/ai/movement/maneuvers/EscortTargetManeuverV3;getTargetShip()Lcom/fs/starfarer/combat/CollisionEntity;"),
            remap = false)
    private static CollisionEntity ssoptimizer$guardEscortManeuverTarget(final EscortTargetManeuverV3 maneuver) {
        return maneuver != null ? maneuver.getTargetShip() : null;
    }
}
