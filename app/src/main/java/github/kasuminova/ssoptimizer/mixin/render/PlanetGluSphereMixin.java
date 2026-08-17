package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.bridge.opengl.GluSupport;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.lwjgl.util.glu.Sphere;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 分离模式下把 Planet 的 GLU 球体绘制转发到渲染队列。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.entities.terrain.Planet#render3d}<br>
 * 注入动机：{@code render3d} 内 7 处 {@code Sphere.draw} 调用——GLU Sphere 由
 * System 类加载器加载，内部引用真实 GL11，transformer 链处理不到（机制见
 * {@link GluSupport} 类注释）。分离模式主线程无 GL context，标题界面背景
 * 战斗中的行星一渲染即以 "No OpenGL context found" 崩溃。<br>
 * 注入效果：分离模式下全部 {@code sphere.draw} 调用点改由
 * {@link GluSupport#enqueueSphereDraw} 录制进渲染队列；非分离模式保持原调用。
 */
@Mixin(targets = "com.fs.starfarer.combat.entities.terrain.Planet")
public abstract class PlanetGluSphereMixin {

    @Redirect(method = "render3d", remap = false,
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/util/glu/Sphere;draw(FII)V"))
    private void ssoptimizer$redirectGluSphereDraw(final Sphere sphere, final float radius,
                                                   final int slices, final int stacks) {
        if (RenderThreadMode.isEnabled()) {
            GluSupport.enqueueSphereDraw(sphere, radius, slices, stacks);
        } else {
            sphere.draw(radius, slices, stacks);
        }
    }
}
