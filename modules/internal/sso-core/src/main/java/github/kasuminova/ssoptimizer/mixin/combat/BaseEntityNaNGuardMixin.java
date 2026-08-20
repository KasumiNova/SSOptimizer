package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 结构值写入 NaN/Inf 守卫的注入层。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.BaseEntity#setHitpoints(float)}<br>
 * 注入动机：全实体结构值写入收口（Ship 覆写经 super 调用至此），兜底所有绕过
 * 伤害守卫的结构写入路径。<br>
 * 注入效果：HEAD 拒绝 NaN/Inf 写入（保留原值）并留取证日志；逻辑委托 {@link CombatNaNGuard}。
 */
@Mixin(targets = GameClassNames.BASE_ENTITY_DOTTED, remap = false)
public abstract class BaseEntityNaNGuardMixin {
    @Inject(method = "setHitpoints", at = @At("HEAD"), cancellable = true)
    private void ssoptimizer$nanHitpointsGuard(final float value, final CallbackInfo ci) {
        if (CombatNaNGuard.shouldRejectHitpoints(value, this)) {
            ci.cancel();
        }
    }
}
