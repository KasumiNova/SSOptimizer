package ssoptimizer.standalone.host;

import com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.Mixins;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

public final class StandaloneTweaker implements ITweaker {
    private static final String SHARED = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.SharedBootstrap";
    private static final String HYBRID = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.HybridWeaverTransformer";
    private static final String GL = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.RenderThreadRedirectTransformer";
    private boolean optimizerEnabled;
    private List<String> arguments = List.of();

    @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        arguments = List.copyOf(args);
    }

    @Override public void injectIntoClassLoader(LaunchClassLoader loader) {
        var system = (RfbSystemClassLoader) ClassLoader.getSystemClassLoader();
        try {
            LaunchPlan plan = LaunchPlan.read();
            optimizerEnabled = plan.payload != null;
            loader.addClassLoaderExclusion("ssoptimizer.standalone.host.");
            loader.addClassLoaderExclusion("org.apache.log4j.");
            loader.addTransformerExclusion("org.spongepowered.");
            loader.addTransformerExclusion("com.llamalad7.mixinextras.");
            system.childDelegations.add("com.fs.");
            for (String plugin : plan.plugins) {
                if (!LaunchPlan.isSystem(plugin)) system.childDelegations.add(plugin);
            }
            PreparedSources.install(plan, loader);
            FlavorHost.initialize(plan, loader);
            if (optimizerEnabled) {
                loader.registerTransformer(HYBRID);
                loader.registerTransformer(GL);
            }
            MixinBootstrap.init();
            MixinExtrasBootstrap.init();
            Mixins.addConfiguration("mixins.ssoptimizer.standalone.json");
            if (FlavorHost.NAMESPACE.equals("obf")) Mixins.addConfiguration("mixins.ssoptimizer.obf-save.json");
            if (optimizerEnabled) {
                Mixins.addConfiguration("mixins.ssoptimizer.json");
                startPayload(loader);
            }
            LogManager.getLogger(StandaloneTweaker.class).info("Standalone mounted {} archives, {} mod jars, payload={}",
                    plan.archives.size(), plan.mods.size(), plan.payload);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot mount prepared Standalone archives", e);
        }
    }

    private static void startPayload(LaunchClassLoader loader) {
        try {
            Class<?> entry = Class.forName(SHARED, false, loader);
            MethodHandles.publicLookup().findStatic(entry, "install", MethodType.methodType(void.class, BiConsumer.class))
                    .invokeExact((BiConsumer<String, UnaryOperator<byte[]>>) SystemTransforms::register);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot invoke shared Standalone bootstrap: " + SHARED, e);
        }
    }

    @Override public String getLaunchTarget() {
        return "com.fs.starfarer.StarfarerLauncher";
    }

    @Override public String[] getLaunchArguments() { return arguments.toArray(String[]::new); }
}
