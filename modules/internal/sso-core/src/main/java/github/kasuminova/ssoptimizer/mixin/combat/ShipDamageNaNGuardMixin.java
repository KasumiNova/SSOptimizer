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

/**
 * 伤害入口 NaN/Inf 守卫的注入层。
 * <p>
 * 注入目标：{@code Ship#applyDamageInner(Vector2f, Damage, boolean, boolean, float, Object)}
 * （6 参重载；5 参重载委托至此处，单点覆盖）<br>
 * 注入动机：原版入口守卫 {@code if (damage<=0 && flux<=0) return} 对 NaN 放行，
 * 一次 NaN 伤害即永久污染结构值与辐能（舰船不死、辐能瘫痪）。<br>
 * 注入效果：HEAD 预结算伤害/辐能值，NaN/Inf 时取消整个伤害事件并留指纹日志；
 * 逻辑委托 {@link CombatNaNGuard}。<br>
 * 注：本守卫只覆盖「调用方传入即为坏值」的第一阶段；修正链内部变坏的值由
 * {@code ShipDamageStageTwoProcessor}（ASM）在方法中部的两个锚点做第二阶段整单取消。
 */
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
