package github.kasuminova.ssoptimizer.bridge.opengl;

import org.lwjgl.util.glu.Sphere;

/** 将 System 类加载器中的 GLU 调用提交到渲染线程，供字节码重定向入口使用。 */
public final class GluSupport {

    private GluSupport() {
    }

    /** 调用方必须保证命令执行前球体配置不变。 */
    public static void enqueueSphereDraw(final Sphere sphere, final float radius,
                                         final int slices, final int stacks) {
        BridgeSupport.enqueue(() -> sphere.draw(radius, slices, stacks));
    }
}
