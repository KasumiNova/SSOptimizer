package ssoptimizer.standalone.host;

import github.kasuminova.ssoptimizer.standalone.remap.reflect.ReflectionNames;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.IOException;
import java.nio.file.Path;

final class FlavorHost {
    static final String NAMESPACE = "named";
    static final String SYSTEM_PACKAGE = "github.kasuminova.ssoptimizer.standalone.remap.reflect.";

    private FlavorHost() {}

    static void mount(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        for (Path path : plan.archives) loader.addURL(path.toUri().toURL());
        loader.addClassLoaderExclusion(SYSTEM_PACKAGE);
    }

    static void initialize(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        Path home = Path.of(System.getProperty("ssoptimizer.standalone.home"));
        ReflectionNames.initialize(home.resolve("mappings/game-full.tiny.gz"), plan.game, loader);
        StandaloneTweaker.register(loader, ModCodeSourceTransformer.class.getName());
    }
}
