package github.kasuminova.ssoptimizer.bootstrap;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.api.CompositeAsmClassProcessor;
import github.kasuminova.ssoptimizer.api.ExternalModOptimizer;
import github.kasuminova.ssoptimizer.api.automation.FrameCaptureHook;
import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.api.render.RenderFramePump;
import github.kasuminova.ssoptimizer.api.save.SavePhaseTelemetry;
import github.kasuminova.ssoptimizer.asm.automation.ASTDAutomationCombatPluginProcessor;
import github.kasuminova.ssoptimizer.asm.combat.CollisionImpulseClampProcessor;
import github.kasuminova.ssoptimizer.asm.combat.ShipDamageStageTwoProcessor;
import github.kasuminova.ssoptimizer.asm.ime.*;
import github.kasuminova.ssoptimizer.asm.launcher.LauncherDirectStartProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksBootstrapLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.AITweaksCoreLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.CaseInsensitiveResourceFallbackProcessor;
import github.kasuminova.ssoptimizer.asm.loading.ShipMasteryReflectionLoaderProcessor;
import github.kasuminova.ssoptimizer.asm.loading.TextureLoaderPixelProcessor;
import github.kasuminova.ssoptimizer.asm.util.ColorCtorSanitizeProcessor;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.bridge.opengl.GL11;
import github.kasuminova.ssoptimizer.bridge.opengl.RenderThreadDispatch;
import github.kasuminova.ssoptimizer.common.bench.DebugFrameCapture;
import github.kasuminova.ssoptimizer.common.debug.DebugServerBootstrap;
import github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides;
import github.kasuminova.ssoptimizer.common.logging.VanillaLogNoiseConfigurator;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueueImpl;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.save.UnmarshalPhaseTimer;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.modopt.dcr.DcrModOptimizer;
import io.github.nanoforged.api.CoreModContext;
import io.github.nanoforged.api.INanoCorePlugin;
import org.apache.log4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public final class SSOptimizerCorePlugin implements INanoCorePlugin {
    private static final Logger LOGGER = Logger.getLogger(SSOptimizerCorePlugin.class);

    public SSOptimizerCorePlugin() {
    }

    @Override
    public void onLoad(CoreModContext context) {
        VanillaLogNoiseConfigurator.configure();
        installRenderThreadSeparation();
        registerModuleServices();
        registerAllProcessors(HybridWeaverTransformer::registerProcessor);
        registerGlobalProcessors();
        DebugServerBootstrap.startIfEnabled();
        LOGGER.info("[SSOptimizer] CoreMod loaded — Engine + AI + loading repair phase active, "
                + HybridWeaverTransformer.getProcessorCount() + " processor registrations");
    }

    private static void registerModuleServices() {
        ServiceRegistry.register(RenderFramePump.class, RenderThreadDispatch::runBlocking);
        ServiceRegistry.register(SavePhaseTelemetry.class, UnmarshalPhaseTimer::lastUnmarshalMs);
        ServiceRegistry.register(WeaponAtlasLookup.class, path -> {
            final ShipWeaponAtlas.Region r = ShipWeaponAtlas.lookup(path);
            return r == null ? null : new WeaponAtlasLookup.Region(r.textureId(), r.atlasSize(), r.x(), r.y());
        });
        ServiceRegistry.register(FrameCaptureHook.class, DebugFrameCapture::onDisplayUpdate);
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

    // 渲染队列必须在游戏类加载前安装，确保重定向后的 bridge 调用始终有消费者。
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

    static void registerAllProcessors(BiConsumer<String, AsmClassProcessor> registrator) {
        registerEngineProcessors(registrator);
        registerExternalModOptimizers(registrator);
    }

    static void registerEngineProcessors(BiConsumer<String, AsmClassProcessor> registrator) {
        registerIf(registrator, "launcherdirectstart", GameClassNames.STARFARER_LAUNCHER, new LauncherDirectStartProcessor());
        registerIf(registrator, "textureloader", GameClassNames.TEXTURE_LOADER, new TextureLoaderPixelProcessor());
        // LWJGL 由 RFB 系统类加载器加载，Launch 域 transformer 无法改写，必须经 SystemAsmBridge 注册。
        registerSystemIf("linuxdisplayime", GameClassNames.LINUX_DISPLAY, new LinuxDisplayImeProcessor());
        registerSystemIf("linuxkeyboardime", GameClassNames.LINUX_KEYBOARD, new LinuxKeyboardImeProcessor());
        registerSystemIf("linuxeventime", LinuxEventImeProcessor.TARGET_CLASS, new LinuxEventImeProcessor());
        registerSystemIf("windowsdisplayime", "org/lwjgl/opengl/WindowsDisplay", new WindowsDisplayImeProcessor());
        registerIf(registrator, "astdautomation", ASTDAutomationCombatPluginProcessor.TARGET_CLASS, new ASTDAutomationCombatPluginProcessor());
        registerIf(registrator, "aitweaksbootstraploader", AITweaksBootstrapLoaderProcessor.TARGET_CLASS, new AITweaksBootstrapLoaderProcessor());
        registerIf(registrator, "aitweakscoreloader", AITweaksCoreLoaderProcessor.TARGET_CLASS, new AITweaksCoreLoaderProcessor());
        registerIf(registrator, "shipmasteryreflectionloader", ShipMasteryReflectionLoaderProcessor.TARGET_CLASS, new ShipMasteryReflectionLoaderProcessor());
        registerCompositeIf(registrator,
                GameClassNames.RESOURCE_LOADER,
                new ProcessorToggle("caseinsensitiveresource", new CaseInsensitiveResourceFallbackProcessor()));
        registerIf(registrator, "shipdamagestagetwo", GameClassNames.SHIP, new ShipDamageStageTwoProcessor());
        registerIf(registrator, "collisionimpulseclamp", GameClassNames.COLLISION_HANDLER_IMPL, new CollisionImpulseClampProcessor());
    }

    private static void registerExternalModOptimizers(BiConsumer<String, AsmClassProcessor> registrator) {
        registerModOptimizer(registrator, new DcrModOptimizer());
    }

    private static void registerModOptimizer(BiConsumer<String, AsmClassProcessor> registrator,
                                             ExternalModOptimizer optimizer) {
        String featureKey = optimizer.featureKey();
        if (Boolean.getBoolean("ssoptimizer.disable." + featureKey)) {
            LOGGER.info("[SSOptimizer] External mod optimizer DISABLED via system property: " + featureKey);
            return;
        }
        optimizer.processors().forEach((className, processor) -> {
            registrator.accept(className, processor);
            LOGGER.info("[SSOptimizer] Registered external mod optimizer '" + featureKey
                    + "' processor for " + className);
        });
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

    private static void registerSystemIf(String key, String className, AsmClassProcessor processor) {
        if (Boolean.getBoolean("ssoptimizer.disable." + key)) {
            LOGGER.info("[SSOptimizer] System ASM processor DISABLED via system property: " + key);
            return;
        }
        io.github.nanoforged.api.SystemAsmBridge.register(className, processor::process);
        LOGGER.info("[SSOptimizer] Registered system-domain ASM processor '" + key + "' for " + className);
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
