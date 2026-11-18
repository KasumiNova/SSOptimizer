package ssoptimizer.standalone.host;

import com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader;
import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import github.kasuminova.ssoptimizer.standalone.remap.reflect.ReflectionNames;
import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.service.MixinService;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

public final class StandaloneTweaker implements ITweaker {
    private static final String SHARED = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.SharedBootstrap";
    private static final String HYBRID = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.HybridWeaverTransformer";
    private static final String GL = LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.RenderThreadRedirectTransformer";
    private static final String PROXY = "org.spongepowered.asm.mixin.transformer.Proxy";
    private List<String> arguments = List.of();

    @Override public void acceptOptions(List<String> args, File gameDir, File assetsDir, String profile) {
        arguments = List.copyOf(args);
    }

    @Override public void injectIntoClassLoader(LaunchClassLoader loader) {
        if (!(ClassLoader.getSystemClassLoader() instanceof RfbSystemClassLoader system) || system.getChildLoader() != loader) {
            throw new IllegalStateException("Standalone requires the RFB system class loader and its Launch child");
        }
        try {
            SystemTransforms.checkInstalled();
            LaunchPlan plan = LaunchPlan.read();
            for (Path path : plan.archives) loader.addURL(path.toUri().toURL());
            loader.addClassLoaderExclusion("ssoptimizer.standalone.host.");
            loader.addClassLoaderExclusion(LaunchPlan.REFLECTION_PACKAGE);
            loader.addClassLoaderExclusion("org.apache.log4j.");
            loader.addTransformerExclusion("org.spongepowered.");
            loader.addTransformerExclusion("com.llamalad7.mixinextras.");
            system.childDelegations.addAll(plan.childPackages);
            PreparedSources.install(plan, loader);
            Path home = Path.of(System.getProperty("ssoptimizer.standalone.home"));
            ReflectionNames.initialize(home.resolve("mappings/game-full.tiny.gz"), plan.game, loader);
            register(loader, ModCodeSourceTransformer.class.getName());
            register(loader, HYBRID);
            register(loader, GL);
            MixinBootstrap.init();
            if (!"LaunchWrapper".equals(MixinService.getService().getName())) {
                throw new IllegalStateException("Unexpected Mixin service: " + MixinService.getService().getName());
            }
            MixinExtrasBootstrap.init();
            Mixins.addConfiguration("mixins.ssoptimizer.standalone.json");
            Mixins.addConfiguration("mixins.ssoptimizer.xstream-compat.json");
            Mixins.addConfiguration("mixins.ssoptimizer.json");
            startPayload(loader);
            LogManager.getLogger(StandaloneTweaker.class).info("Standalone mounted {} archives, {} mod jars, payload={}",
                    plan.archives.size(), plan.mods.size(), plan.payload);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot mount prepared Standalone archives", e);
        }
    }

    private static void register(LaunchClassLoader loader, String name) {
        int index = loader.getTransformers().size();
        loader.registerTransformer(name);
        var transformers = loader.getTransformers();
        if (transformers.size() != index + 1 || !transformers.get(index).getClass().getName().equals(name)) {
            throw new IllegalStateException("LaunchWrapper did not register transformer " + name);
        }
    }

    private static void startPayload(LaunchClassLoader loader) {
        try {
            Class<?> entry = Class.forName(SHARED, false, loader);
            if (entry.getClassLoader() != loader) throw new LinkageError("Payload class loaded outside Launch: " + entry);
            MethodHandles.publicLookup().findStatic(entry, "install", MethodType.methodType(void.class, BiConsumer.class))
                    .invokeExact((BiConsumer<String, UnaryOperator<byte[]>>) SystemTransforms::register);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot invoke shared Standalone bootstrap: " + SHARED, e);
        }
    }

    @Override public String getLaunchTarget() {
        List<String> order = Launch.classLoader.getTransformers().stream().map(t -> t.getClass().getName()).toList();
        int hybrid = order.indexOf(HYBRID), gl = order.indexOf(GL);
        if (hybrid < 0 || gl <= hybrid || order.lastIndexOf(PROXY) <= gl
                || MixinEnvironment.getCurrentEnvironment().getPhase() != MixinEnvironment.Phase.DEFAULT) {
            throw new IllegalStateException("Standalone transformer/phase mismatch: " + order + ", phase="
                    + MixinEnvironment.getCurrentEnvironment().getPhase());
        }
        LogManager.getLogger(StandaloneTweaker.class).info("Launching RC8, Mixin service=LaunchWrapper phase=DEFAULT, transformers={}", order);
        return "com.fs.starfarer.StarfarerLauncher";
    }

    @Override public String[] getLaunchArguments() { return arguments.toArray(String[]::new); }
}
