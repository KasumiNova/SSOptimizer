package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import github.kasuminova.ssoptimizer.common.combat.ShieldAlwaysOnGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 无盾船体必须跳过 {@code toggleOn()}，其余推进逻辑保持原样。 */
@Mixin(targets = GameClassNames.SHIELD_ALWAYS_ON_DOTTED, remap = false)
public abstract class ShieldAlwaysOnNullShieldMixin {
    @Redirect(
            method = "advanceInCombat(Lcom/fs/starfarer/api/combat/ShipAPI;F)V",
            at = @At(value = "INVOKE", target = "Lcom/fs/starfarer/api/combat/ShieldAPI;isOn()Z"),
            require = 1,
            remap = false)
    private boolean ssoptimizer$nullShieldSafeIsOn(final ShieldAPI shield,
                                                   final ShipAPI ship,
                                                   final float amount) {
        return ShieldAlwaysOnGuard.isShieldOnOrAbsent(shield, ship);
    }
}
