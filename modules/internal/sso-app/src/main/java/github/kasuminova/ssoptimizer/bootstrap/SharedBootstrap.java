package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.automation.FrameCaptureHook;
import github.kasuminova.ssoptimizer.api.font.FontDraw;
import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.api.render.RenderFramePump;
import github.kasuminova.ssoptimizer.api.render.SaveOverlayFrames;
import github.kasuminova.ssoptimizer.api.save.SavePhaseTelemetry;
import github.kasuminova.ssoptimizer.asm.automation.ASTDAutomationCombatPluginProcessor;
import github.kasuminova.ssoptimizer.asm.combat.CollisionImpulseClampProcessor;
import github.kasuminova.ssoptimizer.asm.combat.ShipDamageStageTwoProcessor;
import github.kasuminova.ssoptimizer.asm.ime.*;
import github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksBootstrapLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksCoreLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ShipMasteryReflectionLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.util.ColorCtorSanitizeProcessor;
import github.kasuminova.ssoptimizer.bridge.opengl.FontDrawImpl;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.bridge.opengl.RenderThreadDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.SaveOverlayFramesImpl;
import github.kasuminova.ssoptimizer.common.bench.DebugFrameCapture;
import github.kasuminova.ssoptimizer.common.debug.DebugServerBootstrap;
import github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides;
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.save.UnmarshalPhaseTimer;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;

import java.io.InputStream;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

public final class SharedBootstrap {
    private static final Logger LOGGER = Logger.getLogger(SharedBootstrap.class);
    private static boolean installed;

    private SharedBootstrap() { }

    public static synchronized void install(final BiConsumer<String, UnaryOperator<byte[]>> systemTransforms) {
        if (installed) {
            throw new IllegalStateException("SSOptimizer 已由其他宿主安装");
        }
        installed = true;
        VanillaLogNoiseConfigurator.configure();
        installRenderThreadSeparation();
        registerModuleServices();
        registerEngineProcessors(HybridWeaverTransformer::registerProcessor, systemTransforms);
        registerGlobalProcessors();
        DebugServerBootstrap.startIfEnabled();
        LOGGER.info("[SSOptimizer] 共享处理器注册完成: "
                + HybridWeaverTransformer.getProcessorCount() + " 个处理器");
    }

    private static void registerModuleServices() {
        ServiceRegistry.register(RenderFramePump.class, RenderThreadDispatch::runBlocking);
        ServiceRegistry.register(SaveOverlayFrames.class, new SaveOverlayFramesImpl());
        ServiceRegistry.register(SavePhaseTelemetry.class, UnmarshalPhaseTimer::lastUnmarshalMs);
        ServiceRegistry.register(WeaponAtlasLookup.class, path -> {
            final ShipWeaponAtlas.Region r = ShipWeaponAtlas.lookup(path);
            return r == null ? null : new WeaponAtlasLookup.Region(r.textureId(), r.atlasSize(), r.x(), r.y());
        });
        ServiceRegistry.register(FrameCaptureHook.class, DebugFrameCapture::onDisplayUpdate);
        ServiceRegistry.register(FontDraw.class, new FontDrawImpl());
        ServiceRegistry.register(FontResourceOverride.class, new FontResourceOverride() {
            @Override
            public String normalize(final String resourcePath) {
                return OriginalGameFontOverrides.normalize(resourcePath);
            }

            @Override
            public boolean isEnabled() {
                return OriginalGameFontOverrides.isEnabled();
            }

            @Override
            public boolean isOverriddenPath(final String normalizedPath) {
                return OriginalGameFontOverrides.isOverriddenPath(normalizedPath);
            }

            @Override
            public InputStream openStream(final String resourcePath) {
                return OriginalGameFontOverrides.openStream(resourcePath);
            }
        });
    }

    private static void installRenderThreadSeparation() {
        if (!RenderThreadMode.isEnabled()) {
            return;
        }
        RenderQueueImpl queue = new RenderQueueImpl();
        GL11.install(queue);
        ServiceRegistry.register(github.kasuminova.ssoptimizer.api.render.MappedWriteBridge.class,
                github.kasuminova.ssoptimizer.bridge.opengl.MappedWriteBridgeImpl.get());
        RenderQueueImpl.frameEndMarkerHook(
                github.kasuminova.ssoptimizer.bridge.opengl.MappedWriteBridgeImpl::frameEndMarker);
        LOGGER.info("[SSOptimizer] 渲染线程分离模式已启用：RenderQueue 已安装，"
                + "GL 调用将经 ASM 重定向录制到 " + RenderQueueImpl.RENDER_THREAD_NAME);
    }

    static void registerEngineProcessors(BiConsumer<String, AsmClassProcessor> registrator,
                                         BiConsumer<String, UnaryOperator<byte[]>> systemTransforms) {
        registerIf(registrator, "launcherdirectstart", GameClassNames.STARFARER_LAUNCHER, new LauncherDirectStartProcessor());
        registerSystemIf(systemTransforms, "linuxdisplayime", GameClassNames.LINUX_DISPLAY, new LinuxDisplayImeProcessor());
        registerSystemIf(systemTransforms, "linuxkeyboardime", GameClassNames.LINUX_KEYBOARD, new LinuxKeyboardImeProcessor());
        registerSystemIf(systemTransforms, "linuxeventime", LinuxEventImeProcessor.TARGET_CLASS, new LinuxEventImeProcessor());
        registerSystemIf(systemTransforms, "windowsdisplayime", "org/lwjgl/opengl/WindowsDisplay", new WindowsDisplayImeProcessor());
        registerIf(registrator, "astdautomation", ASTDAutomationCombatPluginProcessor.TARGET_CLASS, new ASTDAutomationCombatPluginProcessor());
        registerIf(registrator, "aitweaksbootstraploader", AITweaksBootstrapLoaderProcessor.TARGET_CLASS, new AITweaksBootstrapLoaderProcessor());
        registerIf(registrator, "aitweakscoreloader", AITweaksCoreLoaderProcessor.TARGET_CLASS, new AITweaksCoreLoaderProcessor());
        registerIf(registrator, "shipmasteryreflectionloader", ShipMasteryReflectionLoaderProcessor.TARGET_CLASS, new ShipMasteryReflectionLoaderProcessor());
        registerIf(registrator, "shipdamagestagetwo", GameClassNames.SHIP, new ShipDamageStageTwoProcessor());
        registerIf(registrator, "collisionimpulseclamp", GameClassNames.COLLISION_HANDLER_IMPL, new CollisionImpulseClampProcessor());
    }

    private static void registerGlobalProcessors() {
        if (Boolean.getBoolean("ssoptimizer.disable.colorsanitize")) {
            LOGGER.info("[SSOptimizer] Processor DISABLED via system property: colorsanitize");
            return;
        }
        HybridWeaverTransformer.registerGlobalProcessor(new ColorCtorSanitizeProcessor());
    }

    private static void registerIf(BiConsumer<String, AsmClassProcessor> registrator,
                                   String key, String className, AsmClassProcessor processor) {
        if (Boolean.getBoolean("ssoptimizer.disable." + key)) {
            LOGGER.info("[SSOptimizer] Processor DISABLED via system property: " + key);
            return;
        }
        registrator.accept(className, processor);
    }

    private static void registerSystemIf(BiConsumer<String, UnaryOperator<byte[]>> systemTransforms,
                                         String key, String className, AsmClassProcessor processor) {
        if (Boolean.getBoolean("ssoptimizer.disable." + key)) {
            LOGGER.info("[SSOptimizer] System ASM processor DISABLED via system property: " + key);
            return;
        }
        systemTransforms.accept(className, processor::process);
        LOGGER.info("[SSOptimizer] Registered system-domain ASM processor '" + key + "' for " + className);
    }

}
