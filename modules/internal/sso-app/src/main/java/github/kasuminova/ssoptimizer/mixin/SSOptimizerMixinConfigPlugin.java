package github.kasuminova.ssoptimizer.mixin;

import github.kasuminova.ssoptimizer.common.render.ShipEngineRenderToggle;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrBatchCompatibility;
import org.apache.log4j.Logger;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SSOptimizerMixinConfigPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerMixinConfigPlugin.class);

    public static final String AI_PARALLEL_DISABLE_PROPERTY = "ssoptimizer.disable.aiparallel";

    private static final Map<String, String> DISABLE_KEYS = Map.of(
            ".ime.TooltipTextFieldFactoryMixin", "tooltiptextfieldime",
            ".ime.SettingsTextFieldFactoryMixin", "settingstextfieldime",
            ".ime.TextFieldImplementationMixin", "textfieldimplime",
            ".render.OriginalFontResourceStreamMixin", "originalfontstream",
            ".loading.TextureLoaderMixin", "textureloader",
            ".loading.TextureLoaderUploadMixin", "textureloader",
            ".loading.TextureLoaderUploadDirectMixin", "textureloader",
            ".loading.ResourceLoaderOpenStreamFallbackMixin", "caseinsensitiveresource",
            ".modopt.dcr.DcrOnGameLoadBatchMixin", "dcr",
            ".modopt.dcr.DcrSerializationManagerBatchMixin", "dcr");

    private Boolean dcrNeedsBatchWindow;

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
        if (mixinClassName.endsWith(".modopt.dcr.DcrOnGameLoadBatchMixin")
                || mixinClassName.endsWith(".modopt.dcr.DcrSerializationManagerBatchMixin")) {
            return needsDcrBatchWindow();
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
        // 纹理上传的调用目标随渲染线程模式变化：开启时是 bridge 调用，关闭时是原始 LWJGL 调用。
        if (mixinClassName.endsWith(".loading.TextureLoaderUploadMixin") && !RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.endsWith(".loading.TextureLoaderUploadDirectMixin") && RenderThreadMode.isEnabled()) {
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

    private boolean needsDcrBatchWindow() {
        if (dcrNeedsBatchWindow == null) {
            try {
                final ClassNode plugin = MixinService.getService().getBytecodeProvider().getClassNode(
                        "data.scripts.combatanalytics.DetailedCombatResultsModPlugin", false);
                dcrNeedsBatchWindow = DcrBatchCompatibility.needsBatchWindow(plugin);
            } catch (ClassNotFoundException e) {
                LOGGER.info("[SSOptimizer] 未找到 DCR 插件类，跳过批量保存 Mixin");
                dcrNeedsBatchWindow = false;
                return false;
            } catch (IOException | IllegalStateException e) {
                LOGGER.error("[SSOptimizer] 无法识别 DCR 读档保存实现", e);
                throw new IllegalStateException("无法识别 DCR 读档保存实现", e);
            }
            LOGGER.info(dcrNeedsBatchWindow
                    ? "[SSOptimizer] DCR 使用逐条保存，启用批量保存窗口"
                    : "[SSOptimizer] DCR 已使用 saveCombatResults 批量保存，跳过旧版批量保存 Mixin");
        }
        return dcrNeedsBatchWindow;
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