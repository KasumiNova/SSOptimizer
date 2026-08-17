package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.render.RenderThreadRedirector;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.log4j.Logger;

/**
 * 渲染线程分离模式的 GL owner 重定向 transformer（catch-all 字节码改写）。
 * <p>
 * 经 {@code coremod.toml} 的 {@code [asm] transformers} 声明，注册进 LaunchClassLoader
 * transformer 链，<b>必须排在 {@link HybridWeaverTransformer} 之后</b>（见
 * {@code app/build.gradle.kts} 的 {@code asmTransformers} 列表）：
 * HybridWeaver 的 {@code CombatStateProcessor} 先把战斗遍历里的 {@code GL11.glFinish}
 * 改写为 hook 调用，本 transformer 再跑时该调用点 owner 已不在改写表内，不会误伤。
 * <p>
 * <b>为什么 Mixin 不可行</b>：见 {@link RenderThreadRedirector} 类 javadoc——
 * 改写对象是开放类集合中任意方法体内的 owner 引用，Mixin 的逐目标注入模型不适用。
 * <p>
 * 语义与契约：
 * <ul>
 *   <li>{@code -Dssoptimizer.renderthread.enable=false}（默认）时完全 no-op，
 *       原字节直返，零风险零开销；</li>
 *   <li>RFB 契约：transform 必须返回原字节，禁止返回 null
 *       （同 {@link HybridWeaverTransformer} 的契约警告）；</li>
 *   <li>重入防护：{@link RenderThreadRedirector#redirect} 本身不触发类加载
 *       （ClassReader 纯解析，ClassWriter 不重算帧），但镜像表构建会读资源、
 *       日志可能触发类初始化，沿用 {@link HybridWeaverTransformer} 的
 *       IN_FLIGHT 先例做防御性透传。</li>
 * </ul>
 */
public final class RenderThreadRedirectTransformer implements IClassTransformer {
    private static final Logger LOGGER = Logger.getLogger(RenderThreadRedirectTransformer.class);

    /** 重入防护（先例见 HybridWeaverTransformer.IN_FLIGHT 注释）。 */
    private static final ThreadLocal<Boolean> IN_FLIGHT = new ThreadLocal<>();

    /**
     * LaunchWrapper 无参构造实例化入口；全部逻辑委托 {@link RenderThreadRedirector}，
     * 实例本身不持有状态。
     */
    public RenderThreadRedirectTransformer() {
    }

    /**
     * {@inheritDoc}
     * <p>
     * flag 关闭时原样返回；改写失败记 error 并返回原字节（宁可该类不分离，
     * 不让单个类阻断游戏启动——崩溃面由运行时未镜像告警与异常暴露）。
     */
    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) {
            return null;
        }
        // flag 检查不经 RenderThreadMode.isEnabled()（编译期内联常量，无类引用）：
        // transformer 链上引用我们自己的类会触发「类加载中再触发类加载」的
        // ClassCircularityError（RenderThreadMode 已踩过，见 RenderThreadRedirector 注释）
        if (!Boolean.getBoolean(RenderThreadMode.ENABLE_PROPERTY)) {
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
