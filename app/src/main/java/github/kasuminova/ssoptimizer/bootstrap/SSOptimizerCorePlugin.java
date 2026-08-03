package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.CompositeAsmClassProcessor;
import github.kasuminova.ssoptimizer.asm.automation.ASTDAutomationCombatPluginProcessor;
import github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor;
import github.kasuminova.ssoptimizer.asm.ime.*;
import github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor;
import github.kasuminova.ssoptimizer.asm.loading.CaseInsensitiveResourceFallbackProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor;
import github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor;
import github.kasuminova.ssoptimizer.asm.render.CombatStateProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrModOptimizer;
import io.github.nanoforged.api.CoreModContext;
import io.github.nanoforged.api.INanoCorePlugin;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * SSOptimizer 的 NanoForge coremod 入口插件（{@code coremod.toml} 的 {@code pluginClass}）。
 * <p>
 * 取代 javaagent 时代的 {@code SSOptimizerAgent.premain}：NanoForge 在 tweaker
 * {@code injectIntoClassLoader} 阶段完成 transformer 注册与 Mixin config 登记后回调
 * {@link #onLoad}，此处把全部 ASM 处理器写入 {@link HybridWeaverTransformer} 的静态注册表。
 * 时序上 onLoad 早于任何游戏类加载，weaver 的 {@code transform} 只在游戏类加载时懒读
 * 注册表，故「onLoad 写、transform 读」是安全模式。
 * <p>
 * 本插件只接管字节码注入；ImageIO 配置、日志降噪、纹理缓存等运行时组件仍由游戏原生
 * {@code SSOptimizerModPlugin.onApplicationLoad} 负责（双轨保留）。
 */
public final class SSOptimizerCorePlugin implements INanoCorePlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerCorePlugin.class);

    /**
     * LaunchWrapper 实例化入口（公开无参构造）。
     */
    public SSOptimizerCorePlugin() {
    }

    /**
     * coremod 装配完成回调：注册全部引擎级与外部模组 ASM 处理器。
     *
     * @param context NanoForge 运行上下文（当前不使用，路径均按既有系统属性约定解析）
     */
    @Override
    public void onLoad(CoreModContext context) {
        registerAllProcessors(HybridWeaverTransformer::registerProcessor);
        LOGGER.info("[SSOptimizer] CoreMod loaded — Engine + AI + loading repair phase active, "
                + HybridWeaverTransformer.getProcessorCount() + " processor registrations");
    }

    /**
     * 注册全部 ASM 处理器到指定接收器。
     * <p>
     * 生产路径接收器为 {@link HybridWeaverTransformer#registerProcessor}；测试可传入
     * 自有收集器以避免静态注册表污染。
     *
     * @param registrator 处理器注册接收器（类名可为点号或斜杠格式）
     */
    static void registerAllProcessors(BiConsumer<String, AsmClassProcessor> registrator) {
        registerEngineProcessors(registrator);
        registerExternalModOptimizers(registrator);
    }

    /**
     * 注册所有引擎级 ASM 处理器。
     * <p>
     * 每个处理器可通过系统属性 {@code ssoptimizer.disable.<key>} 单独禁用。
     *
     * @param registrator 处理器注册接收器
     */
    static void registerEngineProcessors(BiConsumer<String, AsmClassProcessor> registrator) {
        registerIf(registrator, "launcherdirectstart", GameClassNames.STARFARER_LAUNCHER, new LauncherDirectStartProcessor());
        registerIf(registrator, "textureloader", GameClassNames.TEXTURE_LOADER, new TextureLoaderPixelProcessor());
        registrator.accept(GameClassNames.COMBAT_STATE, new CombatStateProcessor());
        registerIf(registrator, "linuxdisplayime", GameClassNames.LINUX_DISPLAY, new LinuxDisplayImeProcessor());
        registerIf(registrator, "linuxkeyboardime", GameClassNames.LINUX_KEYBOARD, new LinuxKeyboardImeProcessor());
        registerIf(registrator, "astdautomation", ASTDAutomationCombatPluginProcessor.TARGET_CLASS, new ASTDAutomationCombatPluginProcessor());
        registerIf(registrator, "windowsdisplayime", "org/lwjgl/opengl/WindowsDisplay", new WindowsDisplayImeProcessor());
        registerIf(registrator, "tooltiptextfieldime", GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE, new TooltipTextFieldFactoryProcessor());
        registerIf(registrator, "settingstextfieldime", GameClassNames.STARFARER_SETTINGS_TEXT_FIELD_OWNER, new SettingsTextFieldFactoryProcessor());
        registerIf(registrator, "textfieldimplime", GameClassNames.TEXT_FIELD_IMPL, new TextFieldImplementationProcessor());
        registerCompositeIf(registrator,
                GameClassNames.RESOURCE_LOADER,
                new ProcessorToggle("originalfontstream", new OriginalFontResourceStreamProcessor()),
                new ProcessorToggle("resourcefilecache", new ResourceLoaderFileAccessProcessor()),
                new ProcessorToggle("caseinsensitiveresource", new CaseInsensitiveResourceFallbackProcessor()));
    }

    /**
     * 注册外部模组性能优化处理器（当前为 DetailedCombatResults 的 DCR 优化集合）。
     * <p>
     *  javaagent 时代经 ServiceLoader SPI 发现；coremod 化后源码已收编进主模块，
     * 此处直接实例化注册。总开关 {@code -Dssoptimizer.disable.dcr=true} 与
     * {@code -Dssoptimizer.disable.dcrzstd=true} 子开关保持不变。
     *
     * @param registrator 处理器注册接收器
     */
    private static void registerExternalModOptimizers(BiConsumer<String, AsmClassProcessor> registrator) {
        DcrModOptimizer dcr = new DcrModOptimizer();
        String featureKey = dcr.featureKey();
        if (Boolean.getBoolean("ssoptimizer.disable." + featureKey)) {
            LOGGER.info("[SSOptimizer] External mod optimizer DISABLED via system property: " + featureKey);
            return;
        }
        dcr.processors().forEach((className, processor) -> {
            registrator.accept(className, processor);
            LOGGER.info("[SSOptimizer] Registered external mod optimizer '" + featureKey
                    + "' processor for " + className);
        });
    }

    private static void registerIf(BiConsumer<String, AsmClassProcessor> registrator,
                                   String key, String className, AsmClassProcessor processor) {
        if (Boolean.getBoolean("ssoptimizer.disable." + key)) {
            LOGGER.info("[SSOptimizer] Processor DISABLED via system property: " + key);
            return;
        }
        registrator.accept(className, processor);
    }

    private static void registerCompositeIf(BiConsumer<String, AsmClassProcessor> registrator,
                                            String className,
                                            ProcessorToggle... toggles) {
        List<AsmClassProcessor> enabled = new ArrayList<>(toggles.length);
        for (ProcessorToggle toggle : toggles) {
            if (Boolean.getBoolean("ssoptimizer.disable." + toggle.key())) {
                LOGGER.info("[SSOptimizer] Processor DISABLED via system property: " + toggle.key());
                continue;
            }
            enabled.add(toggle.processor());
        }
        if (enabled.isEmpty()) {
            return;
        }
        registrator.accept(className, CompositeAsmClassProcessor.of(enabled.toArray(AsmClassProcessor[]::new)));
    }

    private record ProcessorToggle(String key,
                                   AsmClassProcessor processor) {
    }
}
