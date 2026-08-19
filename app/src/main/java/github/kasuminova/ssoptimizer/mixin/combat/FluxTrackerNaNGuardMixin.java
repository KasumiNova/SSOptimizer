package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 辐能写入 NaN/Inf 守卫的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.ship.FluxTracker#increaseFlux(float, boolean, boolean, boolean, boolean)}
 * （5 参版；4 参重载委托至此，单点覆盖）<br>
 * 注入动机：辐能写入收口——原版各比较守卫对 NaN 全部放行，一次 NaN 写入即
 * 不通超载、不消散、不钳制，辐能系统永久瘫痪。<br>
 * 注入效果：HEAD 拒绝 NaN/Inf 辐能量并留取证日志；返回值按「已接受」处理
 * （与原版 amount==0 早退路径语义一致，干扰最小）；逻辑委托 {@link CombatNaNGuard}。
 */
@Mixin(targets = GameClassNames.FLUX_TRACKER_DOTTED, remap = false)
public abstract class FluxTrackerNaNGuardMixin {
    @Inject(method = "increaseFlux(FZZZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanFluxGuard(final float amount, final boolean forceOverload,
                                          final boolean noOverload, final boolean hardFlux,
                                          final boolean noHardFluxClamp,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if (CombatNaNGuard.shouldRejectFlux(amount, this)) {
            cir.setReturnValue(true);
        }
    }
}
