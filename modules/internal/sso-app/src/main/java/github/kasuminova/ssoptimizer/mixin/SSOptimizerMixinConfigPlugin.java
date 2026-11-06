package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderToggle;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SSOptimizerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerMixinConfigPlugin.class);

    public static final String AI_PARALLEL_DISABLE_PROPERTY = "ssoptimizer.disable.aiparallel";

    /** Mixin 类名后缀到 {@code ssoptimizer.disable.<key>} 织入开关键的映射。 */
    private static final Map<String, String> DISABLE_KEYS = Map.of(
            ".ime.TooltipTextFieldFactoryMixin", "tooltiptextfieldime",
            ".ime.SettingsTextFieldFactoryMixin", "settingstextfieldime",
            ".ime.TextFieldImplementationMixin", "textfieldimplime",
            ".render.OriginalFontResourceStreamMixin", "originalfontstream");

    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName,
                                    final String mixinClassName) {
        for (final java.util.Map.Entry<String, String> entry : DISABLE_KEYS.entrySet()) {
            if (mixinClassName.endsWith(entry.getKey())
                    && Boolean.getBoolean("ssoptimizer.disable." + entry.getValue())) {
                LOGGER.info("[SSOptimizer] Mixin DISABLED via -Dssoptimizer.disable." + entry.getValue()
                        + "=true: " + mixinClassName);
                return false;
            }
        }
        if (mixinClassName.endsWith(".render.EngineRenderMixin") && !ShipEngineRenderToggle.isEnabled()) {
            LOGGER.info("[SSOptimizer] Ship engine render optimization mixin disabled via -D"
                + ShipEngineRenderToggle.ENABLE_PROPERTY + "=false");
            return false;
        }
        // 渲染线程模式关闭时 bridge 不会安装，因此必须禁用依赖顶点流或纹理上传通道的 Mixin。
        if (!RenderThreadMode.isEnabled()
                && (mixinClassName.endsWith(".render.SpriteMixin")
                || mixinClassName.endsWith(".render.BitmapFontRendererMixin"))) {
            LOGGER.info("[SSOptimizer] 渲染线程分离模式已关闭，禁用依赖 bridge 的 Mixin: " + mixinClassName);
            return false;
        }
        // glFinish 的调用目标随渲染线程模式变化：开启时是 bridge 调用，关闭时是原始 LWJGL 调用。
        if (mixinClassName.endsWith(".render.CombatStateTraversalMixin") && !RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.endsWith(".render.CombatStateTraversalDirectMixin") && RenderThreadMode.isEnabled()) {
            return false;
        }
        // 该属性阻止 AI Mixin 织入；运行期软开关则保留织入并串行执行。
        if (mixinClassName.contains(".mixin.ai.")
                && Boolean.getBoolean(AI_PARALLEL_DISABLE_PROPERTY)) {
            LOGGER.info("[SSOptimizer] Parallel AI mixin disabled via -D" + AI_PARALLEL_DISABLE_PROPERTY
                + "=true: " + mixinClassName);
            return false;
        }
        return true;
    }

    @Override
    public void acceptTargets(final Set<String> myTargets,
                              final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName,
                         final ClassNode targetClass,
                         final String mixinClassName,
                         final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName,
                          final ClassNode targetClass,
                          final String mixinClassName,
                          final IMixinInfo mixinInfo) {
    }
}