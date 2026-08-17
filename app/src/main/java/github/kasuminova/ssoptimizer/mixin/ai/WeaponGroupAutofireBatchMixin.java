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
 * 自动开火批处理应用 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.systems.WeaponGroup#advanceAuto}<br>
 * 注入动机：{@link AutofireBatchRunner} 已在并行收集阶段完成白名单插件的
 * advance 与决策读取，原版方法会再次串行执行一遍完整决策流程。<br>
 * 注入效果：HEAD 处检查组内全部插件均命中本帧决策缓存时，在主线程复刻原版
 * 应用段（逐插件 setShipTarget 写入/恢复 + shouldFire 与 holdFire 合成 +
 * weapon.advance）并取消原方法；任一插件未命中则不接管，整组走原版路径
 * （组级原子性，与收集阶段一一对应）。
 */
@Mixin(targets = GameClassNames.WEAPON_GROUP_DOTTED)
public abstract class WeaponGroupAutofireBatchMixin {
    @Shadow(remap = false)
    private List<AutofireAIPlugin> ais;

    @Shadow(remap = false)
    private Ship ship;

    @Shadow(remap = false)
    public abstract boolean isAutofiring();

    /**
     * @author KasumiNova
     * @reason 命中批处理缓存时以主线程回放替代原版串行决策；hulk 舰或自动开火关闭的组
     * 走原版廉价路径（{@code weapon.advance(false, null, f)}），不接管。
     */
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
        // 全员命中：主线程复刻原版应用段（含每轮迭代的 shipTarget 写/恢复）
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
