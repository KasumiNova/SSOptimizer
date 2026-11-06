package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;

/**
 * 开放类集合中的任意 GL owner 引用都需要改写，Mixin 的逐目标注入模型不适用。
 * 与 HybridWeaverTransformer 的改写目标互不重叠，声明顺序不构成依赖；按 RFB 契约返回类字节。
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
            return RenderThreadRedirector.redirect(className, basicClass);
        } catch (Throwable t) {
            LOGGER.error("[SSOptimizer] GL 重定向改写失败（该类保持原字节）: " + className, t);
            return basicClass;
        } finally {
            IN_FLIGHT.remove();
        }
    }
}
