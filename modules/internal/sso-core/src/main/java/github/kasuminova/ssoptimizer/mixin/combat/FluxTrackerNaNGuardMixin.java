package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 辐能写入 NaN/Inf 守卫的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.ship.FluxTracker} 的全部直接写入口——
 * {@code increaseFlux(float, boolean, boolean, boolean, boolean)}（5 参版；2/3/4 参重载
 * 委托至此，单点覆盖）、{@code setCurrFlux(float)}、{@code setMinFlux(float)}
 * （{@code setHardFlux} 委托 {@code setMinFlux}，单点覆盖）。<br>
 * 注入动机：辐能写入收口——原版各比较守卫对 NaN 全部放行（{@code setCurrFlux(F)V} 的
 * {@code fcmpg} 下界钳制对 NaN 视为 ≥0 直接写入），一次 NaN 写入即不通超载、不消散、
 * 不钳制，辐能系统永久瘫痪。<br>
 * 注入效果：HEAD 拒绝 NaN/Inf 辐能量并留取证日志（含 owner 舰船身份）；
 * {@code increaseFlux} 返回值按「已接受」处理（与原版 amount==0 早退路径语义一致，
 * 干扰最小）；直写入口直接取消调用、保留原有有限状态；逻辑委托 {@link CombatNaNGuard}。
 */
@Mixin(targets = GameClassNames.FLUX_TRACKER_DOTTED, remap = false)
public abstract class FluxTrackerNaNGuardMixin {
    /** 辐能条所属舰船（字节码字段名 {@code ship}），用于取证日志的 owner 身份。 */
    @Shadow
    private Ship ship;

    @Inject(method = "increaseFlux(FZZZZ)Z", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanFluxGuard(final float amount, final boolean forceOverload,
                                          final boolean noOverload, final boolean hardFlux,
                                          final boolean noHardFluxClamp,
                                          final CallbackInfoReturnable<Boolean> cir) {
        if (CombatNaNGuard.shouldRejectFlux(amount, this, ship)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "setCurrFlux(F)V", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanCurrFluxGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectFlux(value, this, ship)) {
            ci.cancel();
        }
    }

    @Inject(method = "setMinFlux(F)V", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanMinFluxGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectFlux(value, this, ship)) {
            ci.cancel();
        }
    }
}
