package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.bridge.opengl.DisplayListGuard;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code Planet.render3d(LVector2f; F)} 内对公有字段 {@code GLListManager.suspend}
 * 两处直接写（named 源码 Planet.java:483/498，行星 3D 渲染期全局暂停 display
 * list 缓存）的转接：字段语义已迁入 {@link DisplayListGuard} 的 volatile
 * 标志（{@code GLListManagerMixin} 的方法重写拦截不到字段访问）。
 */
@Mixin(targets = GameClassNames.PLANET_DOTTED)
public abstract class PlanetDisplayListGuardMixin {
    private static final String RENDER_3D_METHOD =
            "render3d(Lorg/lwjgl/util/vector/Vector2f;F)V";

    @Redirect(method = RENDER_3D_METHOD, remap = false,
            at = @At(value = "FIELD",
                    target = "Lcom/fs/graphics/util/GLListManager;suspend:Z",
                    opcode = Opcodes.PUTSTATIC, ordinal = 0))
    private void ssoptimizer$setSuspendOn(boolean value) {
        DisplayListGuard.setSuspend(value);
    }

    @Redirect(method = RENDER_3D_METHOD, remap = false,
            at = @At(value = "FIELD",
                    target = "Lcom/fs/graphics/util/GLListManager;suspend:Z",
                    opcode = Opcodes.PUTSTATIC, ordinal = 1))
    private void ssoptimizer$setSuspendOff(boolean value) {
        DisplayListGuard.setSuspend(value);
    }
}
