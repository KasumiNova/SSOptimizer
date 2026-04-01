package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.asm.combat.CollisionGridQueryProcessor;
import github.kasuminova.ssoptimizer.asm.font.OriginalFontResourceStreamProcessor;
import github.kasuminova.ssoptimizer.asm.ime.*;
import github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor;
import github.kasuminova.ssoptimizer.asm.loading.*;
import github.kasuminova.ssoptimizer.asm.render.*;
import github.kasuminova.ssoptimizer.common.loading.ImageIoConfigurator;
import github.kasuminova.ssoptimizer.common.logging.LogNoiseFilterConfigurator;
import org.apache.log4j.Logger;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;

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

        inst.addTransformer(new SanitizingTransformer(), true);
        inst.addTransformer(new ReflectionSanitizingTransformer(), true);

        weaverTransformer = new HybridWeaverTransformer();
        registerEngineProcessors(weaverTransformer);
        inst.addTransformer(weaverTransformer, true);

        LOGGER.info("[SSOptimizer] Agent loaded — Engine + AI + loading repair phase active");
    }

    /**
     * 注册所有引擎级 ASM 处理器到混合织入变换器。
     * <p>
     * 每个处理器可通过系统属性 {@code ssoptimizer.disable.<key>} 单独禁用。
     *
     * @param transformer 目标混合织入变换器
     */
    static void registerEngineProcessors(HybridWeaverTransformer transformer) {
        registerIf(transformer, "sprite", "com.fs.graphics.Sprite", new EngineSpriteProcessor());
        registerIf(transformer, "superobject", "com.fs.graphics.super.Object", new EngineSuperObjectProcessor());
        registerIf(transformer, "texturedstrip", "com.fs.starfarer.renderers.o0OO", new EngineTexturedStripRendererProcessor());
        registerIf(transformer, "contrailengine", "com.fs.starfarer.combat.entities.ContrailEngine", new EngineContrailEngineProcessor());
        registerIf(transformer, "aigridquery", "com.fs.starfarer.combat.o0OO.oOoO", new CollisionGridQueryProcessor());
        transformer.registerProcessor("com.fs.starfarer.combat.CombatState", new CombatStateProcessor());
        registerIf(transformer, "smoothparticle", "com.fs.graphics.particle.SmoothParticle", new EngineSmoothParticleProcessor());
        registerIf(transformer, "detailedsmoke", "com.fs.starfarer.renderers.fx.DetailedSmokeParticle", new EngineDetailedSmokeProcessor());
        registerIf(transformer, "generictextureparticle", "com.fs.graphics.particle.GenericTextureParticle", new EngineGenericTextureParticleProcessor());
        registerIf(transformer, "launcherdirectstart", "com.fs.starfarer.StarfarerLauncher", new LauncherDirectStartProcessor());
        registerIf(transformer, "parallelpreload", "com.fs.graphics.L", new ParallelImagePreloadProcessor());
        registerIf(transformer, "textureloader", "com.fs.graphics.TextureLoader", new TextureLoaderPixelProcessor());
        registerIf(transformer, "textureobject", "com.fs.graphics.Object", new TextureObjectBindProcessor());
        registerIf(transformer, "loadingtext", "com.fs.starfarer.loading.LoadingUtils", new LoadingUtilsTextProcessor());
        registerIf(transformer, "linuxdisplayime", "org.lwjgl.opengl.LinuxDisplay", new LinuxDisplayImeProcessor());
        registerIf(transformer, "linuxeventime", "org.lwjgl.opengl.LinuxEvent", new LinuxEventImeProcessor());
        registerIf(transformer, "linuxkeyboardime", "org.lwjgl.opengl.LinuxKeyboard", new LinuxKeyboardImeProcessor());
        registerIf(transformer, "tooltiptextfieldime", "com.fs.starfarer.ui.impl.StandardTooltipV2Expandable", new TooltipTextFieldFactoryProcessor());
        registerIf(transformer, "settingstextfieldime", "com.fs.starfarer.settings.StarfarerSettings$1", new SettingsTextFieldFactoryProcessor());
        registerIf(transformer, "textfieldimplime", "com.fs.starfarer.ui.B", new TextFieldImplementationProcessor());
        registerCompositeIf(transformer,
                "com.fs.util.ooOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOOO",
                new ProcessorToggle("originalfontstream", new OriginalFontResourceStreamProcessor()),
                new ProcessorToggle("resourcefilecache", new ResourceLoaderFileAccessProcessor()));
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
}
