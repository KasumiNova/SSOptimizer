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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Predicate;

final class FlavorHost {
    static final String NAMESPACE = "obf";
    static final String SYSTEM_PACKAGE = "github.kasuminova.ssoptimizer.standalone.obf.";

    private FlavorHost() {}

    static void initialize(LaunchPlan plan, LaunchClassLoader loader) throws IOException {
        System.setProperty("ssoptimizer.disable.save.terrain.zstd", "true");
        loader.addClassLoaderExclusion(SYSTEM_PACKAGE);
        var normalizer = ObfClasses.entriesFor(plan.game);
        var modLocations = new ConcurrentHashMap<URL, Boolean>();
        Predicate<URL> isMod = location -> "file".equals(location.getProtocol()) && modLocations.computeIfAbsent(location, url -> {
            try {
                Path archive = Path.of(url.toURI()).toRealPath();
                return plan.modRoots.stream().anyMatch(archive::startsWith);
            } catch (IOException failure) {
                throw new UncheckedIOException("无法定位模组归档 " + url, failure);
            } catch (URISyntaxException failure) {
                throw new IllegalArgumentException("无效的模组归档 URL " + url, failure);
            }
        });
        ArchiveClassPath.mount(loader, plan.archives, Set.copyOf(plan.game), java.util.Map.of(), isMod, normalizer::apply);
        if (plan.payload == null) return;
        BiFunction<String, byte[], byte[]> normalizeMod = normalizer::apply;
        try {
            Class<?> entry = Class.forName(LaunchPlan.PAYLOAD_PACKAGE + "bootstrap.SharedBootstrap", false, loader);
            MethodHandles.publicLookup().findStatic(entry, "installClassNormalizer", MethodType.methodType(void.class, Predicate.class, BiFunction.class))
                    .invokeExact(isMod, normalizeMod);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("无法安装模组私有类修正器", failure);
        }
    }
}
