package github.kasuminova.ssoptimizer.mixin.combat;

import com.fs.starfarer.combat.CollisionEntity;
import github.kasuminova.ssoptimizer.common.combat.CombatNaNGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 碰撞冲量 NaN/Inf 追踪的注入层（仅取证，不拦截）。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CollisionHandlerImpl#applyCollisionImpulse(CollisionEntity, CollisionEntity, Vector2f)}<br>
 * 注入动机：碰撞冲量是 NaN 在交战双方间的传染通道（一方 NaN 位置/速度会污染对方
 * 速度与角速度），此处日志用于区分运动守卫命中的实体是 NaN 原发还是被传染。<br>
 * 注入效果：HEAD 检测碰撞点与双方速度，命中时留双方实体指纹与调用栈；
 * 逻辑委托 {@link CombatNaNGuard}。
 */
@Mixin(targets = GameClassNames.COLLISION_HANDLER_IMPL_DOTTED, remap = false)
public abstract class CollisionImpulseNaNTraceMixin {
    @Inject(method = "applyCollisionImpulse", at = @At("HEAD"))
    private static void ssoptimizer$nanCollisionTrace(final CollisionEntity a, final CollisionEntity b,
                                                      final Vector2f point,
                                                      final CallbackInfoReturnable<Float> cir) {
        CombatNaNGuard.traceCollisionImpulse(a, b, point);
    }
}
