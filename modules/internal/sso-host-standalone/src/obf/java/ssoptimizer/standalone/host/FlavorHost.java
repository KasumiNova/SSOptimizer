package ssoptimizer.standalone.host;

import github.kasuminova.ssoptimizer.standalone.obf.ObfClasses;
import net.minecraft.launchwrapper.LaunchClassLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.LinkedHashMap;

final class FlavorHost {
    static final String NAMESPACE = "obf";
    static final String SYSTEM_PACKAGE = "github.kasuminova.ssoptimizer.standalone.obf.";

    private FlavorHost() {}

    static void mount(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        var views = new LinkedHashMap<Path, Path>();
        for (int i = 0; i < plan.mods.size(); i++) views.put(plan.modSources.get(i), plan.mods.get(i));
        for (Path path : plan.archives) {
            int index = plan.mods.indexOf(path);
            Path origin = index < 0 ? path : plan.modSources.get(index);
            loader.addURL(index < 0 ? path.toUri().toURL() : ModArchiveView.open(origin, views));
        }
    }

    static void initialize(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        System.setProperty("ssoptimizer.disable.save.terrain.zstd", "true");
        loader.addClassLoaderExclusion(SYSTEM_PACKAGE);
        if (plan.payload == null) return;
        var gameNormalizer = ObfClasses.forGameJars(plan.gameSources);
        BiFunction<URL, byte[], byte[]> normalizer = (location, bytes) -> {
            if (!"file".equals(location.getProtocol())) return bytes;
            try {
                Path archive = Path.of(location.toURI()).toRealPath();
                return plan.modRoots.stream().anyMatch(archive::startsWith) ? gameNormalizer.apply(bytes) : bytes;
            } catch (IOException failure) {
                throw new UncheckedIOException("无法定位自建加载器的归档：" + location, failure);
            } catch (URISyntaxException failure) {
                throw new IllegalArgumentException("无效的模组归档 URL：" + location, failure);
            }
        };
        try {
            Class<?> entry = Class.forName(LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.SharedBootstrap", false, loader);
            if (entry.getClassLoader() != loader) throw new LinkageError("SSOptimizer 负载未由 Launch 定义：" + entry);
            MethodHandles.publicLookup().findStatic(entry, "installClassNormalizer", MethodType.methodType(void.class, BiFunction.class))
                    .invokeExact(normalizer);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("无法安装模组私有类的净化器", failure);
        }
    }
}
