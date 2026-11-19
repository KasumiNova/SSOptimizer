package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.render.ClassDefinitionRedirector;
import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;

/**
 * Launch 域中的 OpenGL 调用和 JDK 类定义调用需要按字节码指令改写，Mixin 无法逐类声明开放的调用方集合。
 */
public final class RenderThreadRedirectTransformer implements IClassTransformer {
    private static final Logger LOGGER = Logger.getLogger(RenderThreadRedirectTransformer.class);

    private static final ThreadLocal<Boolean> IN_FLIGHT = new ThreadLocal<>();

    public RenderThreadRedirectTransformer() {
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        // 此处不得调用 RenderThreadMode.isEnabled()，否则类加载重入会触发 ClassCircularityError。
        if ("false".equalsIgnoreCase(System.getProperty(RenderThreadMode.ENABLE_PROPERTY, "true"))) {
            return basicClass;
        }

        String className = transformedName != null ? transformedName : name;
        if (className == null) {
            return basicClass;
        }

        if (IN_FLIGHT.get() != null) {
            return basicClass;
        }
        IN_FLIGHT.set(Boolean.TRUE);
        try {
            final byte[] redirected = RenderThreadRedirector.redirect(className, basicClass);
            return ClassDefinitionRedirector.rewrite(redirected, getClass().getClassLoader());
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] GL 重定向改写失败（该类保持原字节）: " + className, t);
            return basicClass;
        } finally {
            IN_FLIGHT.remove();
        }
    }
}
