package github.kasuminova.ssoptimizer.mixin.ai;

import com.fs.profiler.Profiler;
import com.fs.starfarer.api.combat.AutofireAIPlugin;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.starfarer.combat.systems.Weapon;
import github.kasuminova.ssoptimizer.common.combat.ai.AutofireBatchRunner;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 组内全部插件都有批处理决策时，由主线程应用决策并取消原方法。
 * 任一插件未命中则整组保持原版执行路径，避免重复推进插件。
 */
@Mixin(targets = GameClassNames.WEAPON_GROUP_DOTTED)
public abstract class WeaponGroupAutofireBatchMixin {
    @Shadow(remap = false)
    private List<AutofireAIPlugin> ais;

    @Shadow(remap = false)
    private Ship ship;

    @Shadow(remap = false)
    public abstract boolean isAutofiring();

    @Inject(method = "advanceAuto", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$applyBatchedDecisions(final boolean selected, final Object param, final float amount,
                                                   final CallbackInfo ci) {
        if (this.ship.isHulk() || !this.isAutofiring()) {
            return;
        }
        final List<AutofireAIPlugin> plugins = this.ais;
        if (plugins.isEmpty()) {
            return;
        }
        for (AutofireAIPlugin plugin : plugins) {
            if (AutofireBatchRunner.getDecision((Weapon) plugin.getWeapon()) == null) {
                return;
            }
        }
        final Ship prevTarget = this.ship.getShipTarget();
        for (AutofireAIPlugin plugin : plugins) {
            final Weapon weapon = (Weapon) plugin.getWeapon();
            final AutofireBatchRunner.Decision decision = AutofireBatchRunner.getDecision(weapon);
            this.ship.setShipTarget(decision.targetShip());
            Profiler.begin("Advancing individual weapon");
            weapon.advance(decision.fire() && !this.ship.isHoldFire() && !this.ship.isHoldFireOneFrame(),
                    decision.aim(), amount);
            Profiler.end();
            this.ship.setShipTarget(prevTarget);
        }
        this.ship.setShipTarget(prevTarget);
        ci.cancel();
    }
}
