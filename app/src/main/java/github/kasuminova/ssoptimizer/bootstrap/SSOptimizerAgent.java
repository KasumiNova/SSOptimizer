package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor;
import github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor;
import github.kasuminova.ssoptimizer.asm.ime.*;
import github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor;
import github.kasuminova.ssoptimizer.asm.loading.CaseInsensitiveResourceFallbackProcessor;
import github.kasuminova.ssoptimizer.asm.loading.LoadingUtilsTextProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor;
import github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor;
import github.kasuminova.ssoptimizer.asm.loading.TextureObjectBindProcessor;
import github.kasuminova.ssoptimizer.asm.render.*;
import github.kasuminova.ssoptimizer.common.loading.ImageIoConfigurator;
import github.kasuminova.ssoptimizer.common.logging.LogNoiseFilterConfigurator;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.List;

/**
 * SSOptimizer 的 javaagent 入口类。
 * <p>
 * 在 {@link #premain} 方法中完成以下初始化：
 * <ul>
 *   <li>配置 ImageIO 和日志降噪</li>
 *   <li>安装引导类搜索路径</li>
 *   <li>初始化 Mixin 框架（失败则回退为纯 ASM 模式）</li>
 *   <li>注册标识符净化变换器</li>
 *   <li>注册所有 ASM 字节码处理器</li>
 * </ul>
 */
public final class SSOptimizerAgent {
    private static final    Logger                  LOGGER = Logger.getLogger(SSOptimizerAgent.class);
    private static volatile Instrumentation         instrumentation;
    private static volatile HybridWeaverTransformer weaverTransformer;
    private static volatile boolean                 mixinAvailable;

    private SSOptimizerAgent() {
    }

    /**
     * javaagent 入口方法，在 JVM 启动时被调用。
     * <p>
     * 初始化 Mixin 框架、注册类名净化变换器和所有 ASM 字节码处理器。
     *
     * @param agentArgs agent 参数字符串（当前未使用）
     * @param inst      JVM 提供的 {@link Instrumentation} 实例
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        instrumentation = inst;

        ImageIoConfigurator.configure();
        LogNoiseFilterConfigurator.configure();

        BootstrapSearchInstaller.install(inst);
        RemappedClasspathInstaller.install(inst);

        try {
            org.spongepowered.asm.launch.MixinBootstrap.init();
            org.spongepowered.asm.mixin.Mixins.addConfiguration("mixins.ssoptimizer.json");
            advanceMixinToDefaultPhase();
            inst.addTransformer(new MixinBridgeTransformer(), true);
            mixinAvailable = true;
            LOGGER.info("[SSOptimizer] Mixin bootstrap ready and bridge installed");
        } catch (Throwable t) {
            mixinAvailable = false;
            LOGGER.warn("[SSOptimizer] Mixin bootstrap failed: " + t.getMessage());
            LOGGER.info("[SSOptimizer] Falling back to ASM-only mode");
        }

        BootstrapPipeline pipeline = createBootstrapPipeline();
        weaverTransformer = pipeline.weaverTransformer();
        for (ClassFileTransformer transformer : pipeline.transformers()) {
            inst.addTransformer(transformer, true);
        }

        LOGGER.info("[SSOptimizer] Agent loaded — Engine + AI + loading repair phase active");
    }

    /**
     * 构建启动阶段的变换器流水线。
     * <p>
     * 该流水线的固定顺序为：运行时重映射、类名净化、反射调用净化、混合织入。
     * 测试通过该方法验证 remap 必须先于 ASM / Mixin patch 执行。
     *
     * @return 启动阶段变换器列表
     */
    static List<ClassFileTransformer> createBootstrapTransformers() {
        return createBootstrapPipeline().transformers();
    }

    private static BootstrapPipeline createBootstrapPipeline() {
        HybridWeaverTransformer weaver = new HybridWeaverTransformer();
        registerEngineProcessors(weaver);

        List<ClassFileTransformer> transformers = List.of(
                new RuntimeRemapTransformer(),
                new SanitizingTransformer(),
                new ReflectionSanitizingTransformer(),
                weaver
        );
        return new BootstrapPipeline(transformers, weaver);
    }

    /**
     * 注册所有引擎级 ASM 处理器到混合织入变换器。
     * <p>
     * 每个处理器可通过系统属性 {@code ssoptimizer.disable.<key>} 单独禁用。
     *
     * @param transformer 目标混合织入变换器
     */
    static void registerEngineProcessors(HybridWeaverTransformer transformer) {
        registerIf(transformer, "sprite", GameClassNames.SPRITE, new EngineSpriteProcessor());
        registerIf(transformer, "bitmapfontrenderer", GameClassNames.BITMAP_FONT_RENDERER, new EngineBitmapFontRendererProcessor());
        registerIf(transformer, "texturedstrip", GameClassNames.TEXTURED_STRIP_RENDERER, new EngineTexturedStripRendererProcessor());
        registerIf(transformer, "contrailengine", GameClassNames.CONTRAIL_ENGINE, new EngineContrailEngineProcessor());
        registerIf(transformer, "aigridquery", GameClassNames.COLLISION_GRID_QUERY, new CollisionGridQueryProcessor());
        transformer.registerProcessor(GameClassNames.COMBAT_STATE, new CombatStateProcessor());
        registerIf(transformer, "smoothparticle", GameClassNames.SMOOTH_PARTICLE, new EngineSmoothParticleProcessor());
        registerIf(transformer, "detailedsmoke", GameClassNames.DETAILED_SMOKE_PARTICLE, new EngineDetailedSmokeProcessor());
        registerIf(transformer, "generictextureparticle", GameClassNames.GENERIC_TEXTURE_PARTICLE, new EngineGenericTextureParticleProcessor());
        registerIf(transformer, "launcherdirectstart", GameClassNames.STARFARER_LAUNCHER, new LauncherDirectStartProcessor());
        registerIf(transformer, "textureloader", GameClassNames.TEXTURE_LOADER, new TextureLoaderPixelProcessor());
        registerIf(transformer, "textureobject", GameClassNames.TEXTURE_OBJECT, new TextureObjectBindProcessor());
        registerIf(transformer, "loadingtext", GameClassNames.LOADING_UTILS, new LoadingUtilsTextProcessor());
        registerIf(transformer, "linuxdisplayime", GameClassNames.LINUX_DISPLAY, new LinuxDisplayImeProcessor());
        registerIf(transformer, "linuxeventime", GameClassNames.LINUX_EVENT, new LinuxEventImeProcessor());
        registerIf(transformer, "linuxkeyboardime", GameClassNames.LINUX_KEYBOARD, new LinuxKeyboardImeProcessor());
        registerIf(transformer, "tooltiptextfieldime", GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE, new TooltipTextFieldFactoryProcessor());
        registerIf(transformer, "settingstextfieldime", GameClassNames.STARFARER_SETTINGS_TEXT_FIELD_OWNER, new SettingsTextFieldFactoryProcessor());
        registerIf(transformer, "textfieldimplime", GameClassNames.TEXT_FIELD_IMPL, new TextFieldImplementationProcessor());
        registerCompositeIf(transformer,
                GameClassNames.RESOURCE_LOADER,
                new ProcessorToggle("originalfontstream", new OriginalFontResourceStreamProcessor()),
                new ProcessorToggle("resourcefilecache", new ResourceLoaderFileAccessProcessor()),
                new ProcessorToggle("caseinsensitiveresource", new CaseInsensitiveResourceFallbackProcessor()));
    }

    private static void registerIf(HybridWeaverTransformer transformer,
                                   String key, String className, AsmClassProcessor processor) {
        if (Boolean.getBoolean("ssoptimizer.disable." + key)) {
            LOGGER.info("[SSOptimizer] Processor DISABLED via system property: " + key);
            return;
        }
        transformer.registerProcessor(className, processor);
    }

    private static void registerCompositeIf(final HybridWeaverTransformer transformer,
                                            final String className,
                                            final ProcessorToggle... toggles) {
        final java.util.List<AsmClassProcessor> enabled = new java.util.ArrayList<>(toggles.length);
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
        transformer.registerProcessor(className, CompositeAsmClassProcessor.of(enabled.toArray(AsmClassProcessor[]::new)));
    }

    /**
     * 获取 JVM 提供的 {@link Instrumentation} 实例。
     */
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    /**
     * 获取混合织入变换器实例。
     */
    public static HybridWeaverTransformer getWeaverTransformer() {
        return weaverTransformer;
    }

    /**
     * 返回 Mixin 框架是否初始化成功。
     */
    public static boolean isMixinAvailable() {
        return mixinAvailable;
    }

    private static void advanceMixinToDefaultPhase() {
        try {
            Class<?> phaseClass = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment$Phase");
            Object defaultPhase = phaseClass.getField("DEFAULT").get(null);
            Method gotoPhase = org.spongepowered.asm.mixin.MixinEnvironment.class
                    .getDeclaredMethod("gotoPhase", phaseClass);
            gotoPhase.setAccessible(true);
            gotoPhase.invoke(null, defaultPhase);
            LOGGER.info("[SSOptimizer] Mixin phase advanced to DEFAULT");
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to advance Mixin environment to DEFAULT phase", t);
        }
    }

    private record ProcessorToggle(String key,
                                   AsmClassProcessor processor) {
    }

    private record BootstrapPipeline(List<ClassFileTransformer> transformers,
                                     HybridWeaverTransformer weaverTransformer) {
    }
}
