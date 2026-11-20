package ssoptimizer.standalone.launch;

import github.kasuminova.ssoptimizer.standalone.remap.ArchivePreparation;
import ssoptimizer.standalone.instance.HistoricalSave;
import ssoptimizer.standalone.instance.InstanceLayout;
import ssoptimizer.standalone.mods.ModArchiveLayout;
import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

final class ArchiveProfile {
    static final String FLAVOR = "dev", NAMESPACE = "named", TOOL = "sso-remap-";
    static final String TOOL_ENTRY = "github/kasuminova/ssoptimizer/standalone/remap/ArchivePreparation.class";
    private static final String MAPPING = "76a5a926934f32fb2a4e75f4fdd61c7879f6f3a9e045dc926cd2873aa821d4b9";

    private ArchiveProfile() {}

    static Path mapping(Path home) { return home.resolve("mappings/game-full.tiny.gz"); }

    static String verify(GameInstall game, Path home, String abi) throws IOException {
        Path file = GameInstall.required(mapping(home));
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
            if (!MAPPING.equals(GameInstall.digest(in))) throw new IOException("发行映射与 RC8 named ABI 不一致：" + file);
        }
        return "开发映射：" + file;
    }

    static Map<Path, Path> prepare(Path home, Path cache, List<Path> game, List<Path> mods, List<Path> libraries, Set<Path> named) throws IOException {
        return ArchivePreparation.prepare(cache.resolve("named"), mapping(home), game, mods, libraries, named);
    }

    static Map<Path, Path> modPaths(Path cache, Map<ModCatalog.Archive, Path> archives) throws IOException {
        return ModArchiveLayout.prepare(cache, archives);
    }

    static Path importSave(GameInstall game, Path home, Path instance, Path source, String slot, String profile) throws IOException {
        InstanceLayout.prepare(game.root(), home, instance);
        return HistoricalSave.importSlot(source, instance, slot, profile, mapping(home));
    }
}
