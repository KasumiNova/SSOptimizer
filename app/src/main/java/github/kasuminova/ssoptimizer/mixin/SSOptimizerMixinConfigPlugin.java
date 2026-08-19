package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderOptimizationToggle;
import org.apache.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * SSOptimizer Mixin 配置插件。
 * <p>
 * 根据 JVM 参数过滤默认关闭的实验性/高风险 Mixin。目前仅让舰船引擎火焰渲染替换
 * {@code render.EngineRenderMixin} 在 {@code -Dssoptimizer.render.shipengine.enable=true}
 * 显式启用时生效，不影响 Sprite、字体、粒子等其他渲染优化。
 */
public final class SSOptimizerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerMixinConfigPlugin.class);

    /** 并行 AI 织入总开关（禁用整个 mixin.ai 包）。 */
    public static final String AI_PARALLEL_DISABLE_PROPERTY = "ssoptimizer.disable.aiparallel";

    /**
     * Mixin 配置加载回调。
     *
     * @param mixinPackage 配置中的 Mixin 包名
     */
    @Override
    public void onLoad(final String mixinPackage) {
    }

    /**
     * 返回用于引用查找的 refmap 配置。
     *
     * @return 当前项目不使用 refmap，返回 null
     */
    @Override
    public String getRefMapperConfig() {
        return null;
    }

    /**
     * 判断指定 Mixin 是否应应用到目标类。
     *
     * @param targetClassName 目标类名
     * @param mixinClassName  Mixin 类名
     * @return 允许应用返回 true，否则返回 false
     */
    @Override
    public boolean shouldApplyMixin(final String targetClassName,
                                    final String mixinClassName) {
        if (mixinClassName.endsWith(".render.EngineRenderMixin") && !ShipEngineRenderOptimizationToggle.isEnabled()) {
            LOGGER.info("[SSOptimizer] Ship engine render optimization mixin disabled by default; enable with -D"
                + ShipEngineRenderOptimizationToggle.ENABLE_PROPERTY + "=true");
            return false;
        }
        // 并行 AI 织入总开关：整套 ai 包 Mixin 同生共死（含线程本地化与并发化），
        // 另有 ssoptimizer.ai.parallel=false 运行期软开关（保留织入、全部内联串行）。
        if (mixinClassName.contains(".mixin.ai.")
                && Boolean.getBoolean(AI_PARALLEL_DISABLE_PROPERTY)) {
            LOGGER.info("[SSOptimizer] Parallel AI mixin disabled via -D" + AI_PARALLEL_DISABLE_PROPERTY
                + "=true: " + mixinClassName);
            return false;
        }
        return true;
    }

    /**
     * 接收 Mixin 目标集合。
     *
     * @param myTargets    当前配置目标集合
     * @param otherTargets 其他配置目标集合
     */
    @Override
    public void acceptTargets(final Set<String> myTargets,
                              final Set<String> otherTargets) {
    }

    /**
     * 返回额外 Mixin 列表。
     *
     * @return 不追加额外 Mixin，返回 null
     */
    @Override
    public List<String> getMixins() {
        return null;
    }

    /**
     * Mixin 应用前回调。
     *
     * @param targetClassName 目标类名
     * @param targetClass     目标类节点
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void preApply(final String targetClassName,
                         final ClassNode targetClass,
                         final String mixinClassName,
                         final IMixinInfo mixinInfo) {
    }

    /**
     * Mixin 应用后回调。
     *
     * @param targetClassName 目标类名
     * @param targetClass     目标类节点
     * @param mixinClassName  Mixin 类名
     * @param mixinInfo       Mixin 信息
     */
    @Override
    public void postApply(final String targetClassName,
                          final ClassNode targetClass,
                          final String mixinClassName,
                          final IMixinInfo mixinInfo) {
    }
}