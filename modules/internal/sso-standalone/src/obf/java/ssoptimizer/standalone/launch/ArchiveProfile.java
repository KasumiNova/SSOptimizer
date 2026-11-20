package ssoptimizer.standalone.launch;

import ssoptimizer.standalone.mods.ModCatalog;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ArchiveProfile {
    static final String FLAVOR = "obf", NAMESPACE = "obf";
    private ArchiveProfile() {}

    static Map<Path, Path> mounts(Path home, Path cache, GameInstall game, List<ModCatalog.Archive> mods,
                                  List<Path> libraries, Path payload) {
        var originals = new LinkedHashMap<Path, Path>();
        for (Path jar : game.gameJars()) originals.put(jar, jar);
        for (var mod : mods) originals.put(mod.path(), mod.path());
        return originals;
    }
}
