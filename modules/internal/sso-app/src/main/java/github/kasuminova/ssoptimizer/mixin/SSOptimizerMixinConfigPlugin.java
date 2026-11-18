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
        if (mixinClassName.endsWith(".save.XStreamFieldAliasingMapperMixin")) {
            return hasXStreamMembers(mixinClassName, targetClassName, node -> {
                String store = "Lcom/thoughtworks/xstream/core/util/MemberStore;";
                return node.fields.stream().anyMatch(f -> f.name.equals("fieldToAliasMap") && f.desc.equals(store))
                        && node.fields.stream().anyMatch(f -> f.name.equals("aliasToFieldMap") && f.desc.equals(store))
                        && hasMethod(node, "getMember", "(Ljava/lang/Class;Ljava/lang/String;" + store + ")Ljava/lang/String;");
            });
        }
        if (mixinClassName.endsWith(".save.XStreamMemberStoreMixin")) {
            return hasXStreamMembers(mixinClassName, targetClassName, node -> node.fields.stream()
                    .anyMatch(f -> f.name.equals("types") && f.desc.equals("Ljava/util/Map;"))
                    && hasMethod(node, "get", "(Ljava/lang/Class;Ljava/lang/String;)Ljava/lang/Object;")
                    && hasMethod(node, "put", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"));
        }
        if (mixinClassName.endsWith(".save.XStreamReflectionConverterMixin")) {
            return hasXStreamMembers(mixinClassName, "com.thoughtworks.xstream.core.util.MemberDictionary",
                    node -> hasMethod(node, "<init>", "()V") && hasMethod(node, "add", "(Ljava/lang/Class;Ljava/lang/String;)Z"));
        }
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
        if (!RenderThreadMode.isEnabled()
                && mixinClassName.endsWith(".render.SpriteMixin")) {
            LOGGER.info("[SSOptimizer] 渲染线程分离模式已关闭，禁用依赖 bridge 的 Mixin: " + mixinClassName);
            return false;
        }
        if (mixinClassName.endsWith(".loading.TextureLoaderUploadMixin") && !RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.endsWith(".loading.TextureLoaderUploadDirectMixin") && RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.endsWith(".render.CombatStateTraversalMixin") && !RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.endsWith(".render.CombatStateTraversalDirectMixin") && RenderThreadMode.isEnabled()) {
            return false;
        }
        if (mixinClassName.contains(".mixin.ai.")
                && Boolean.getBoolean(AI_PARALLEL_DISABLE_PROPERTY)) {
            LOGGER.info("[SSOptimizer] Parallel AI mixin disabled via -D" + AI_PARALLEL_DISABLE_PROPERTY
                + "=true: " + mixinClassName);
            return false;
        }
        return true;
    }

    private static boolean hasMethod(ClassNode node, String name, String descriptor) {
        return node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor));
    }

    private static boolean hasXStreamMembers(String mixin, String type, java.util.function.Predicate<ClassNode> test) {
        try {
            if (test.test(MixinService.getService().getBytecodeProvider().getClassNode(type, false))) return true;
            LOGGER.info("[SSOptimizer] 当前 XStream 的成员签名与 " + mixin + " 不匹配，使用原库实现：" + type);
            return false;
        } catch (ClassNotFoundException ex) {
            LOGGER.info("[SSOptimizer] 当前 XStream 缺少 " + type + "，跳过 " + mixin);
            return false;
        } catch (IOException ex) {
            throw new IllegalStateException("读取 XStream 类结构失败：" + type, ex);
        }
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
