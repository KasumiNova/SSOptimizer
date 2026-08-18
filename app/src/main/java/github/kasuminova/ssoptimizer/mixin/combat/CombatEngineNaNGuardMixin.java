package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.CombatEngine;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 运动状态 NaN/Inf 守卫的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CombatEngine#advanceObjects(float)}<br>
 * 注入动机：该方法是全实体每帧位置/朝向积分的唯一收口，原版限速器
 * {@code scale(600/Inf)} 会把 Inf 速度转化为 NaN 再写进位置——必须先于它拦截。<br>
 * 注入效果：HEAD 遍历全部碰撞实体，NaN/Inf 速度/角速度/朝向归零自愈并留取证日志；
 * 逻辑委托 {@link CombatNaNGuard}。
 */
@Mixin(targets = GameClassNames.COMBAT_ENGINE_DOTTED, remap = false)
public abstract class CombatEngineNaNGuardMixin {
    @Inject(method = "advanceObjects", at = @At("HEAD"))
    private void ssoptimizer$nanMotionGuard(final float amount, final CallbackInfo ci) {
        CombatNaNGuard.checkAllMotion(((CombatEngine) (Object) this).getObjects());
    }
}
