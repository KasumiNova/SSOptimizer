package ssoptimizer.standalone.launch;

import github.kasuminova.ssoptimizer.standalone.remap.ArchivePreparation;
import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

final class ArchiveProfile {
    static final String FLAVOR = "dev", NAMESPACE = "named";
    private ArchiveProfile() {}

    static Map<Path, Path> mounts(Path home, Path cache, GameInstall game, List<ModCatalog.Archive> mods,
                                  List<Path> libraries, Path payload) throws IOException {
        var hierarchy = new ArrayList<>(libraries);
        hierarchy.add(game.core().resolve("lwjgl.jar"));
        hierarchy.add(game.core().resolve("lwjgl_util.jar"));
        if (payload != null) hierarchy.add(payload);
        var named = new HashSet<Path>();
        for (var mod : mods) if (mod.named()) named.add(mod.path());
        var inputs = mods.stream().map(ModCatalog.Archive::path).toList();
        return ArchivePreparation.prepare(cache.resolve("named"), home.resolve("mappings/game-full.tiny.gz"), game.gameJars(), inputs, hierarchy, named);
    }
}
