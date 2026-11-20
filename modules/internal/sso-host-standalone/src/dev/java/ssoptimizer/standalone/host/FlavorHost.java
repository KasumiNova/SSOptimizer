package ssoptimizer.standalone.host;

import github.kasuminova.ssoptimizer.standalone.remap.reflect.ReflectionNames;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.IOException;
import java.nio.file.Path;

final class FlavorHost {
    static final String NAMESPACE = "named";
    static final String SYSTEM_PACKAGE = "github.kasuminova.ssoptimizer.standalone.remap.reflect.";

    private FlavorHost() {}

    static void initialize(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        NamedLoaderUrls.install(plan);
        var mods = new java.util.HashSet<java.net.URL>();
        for (Path path : plan.mods) mods.add(path.toUri().toURL());
        ArchiveClassPath.mount(loader, plan.archives, java.util.Set.of(), plan.origins, mods::contains, NamedLoaderUrls::rewrite);
        loader.addClassLoaderExclusion(SYSTEM_PACKAGE);
        Path home = Path.of(System.getProperty("ssoptimizer.standalone.home"));
        ReflectionNames.initialize(home.resolve("mappings/game-full.tiny.gz"), plan.game, loader, plan.origins);
    }
}
