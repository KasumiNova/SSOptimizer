package ssoptimizer.standalone.launch;

import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class LaunchFiles {
    private LaunchFiles() {}

    static Path prepare(GameInstall game, Path home, Path work, boolean allowManager) throws IOException {
        Path payload = home.resolve("mod/jars/payload.jar");
        Path modsDir = game.root().resolve("mods");
        List<Path> libs = game.libraries(home);
        Path cache = Files.createDirectories(work.resolve("cache"));
        List<String> enabled = null;
        Properties plan;
        try {
            enabled = ModCatalog.enabledIds(modsDir);
            var selection = ModCatalog.selection(modsDir, payload, enabled, ArchiveProfile.NAMESPACE);
            plan = buildPlan(game, payload, home, libs, cache, enabled, selection.archives());
            plan.setProperty("plugin.count", Integer.toString(selection.plugins().size()));
            for (int i = 0; i < selection.plugins().size(); i++) plan.setProperty("plugin." + i, selection.plugins().get(i));
        } catch (IOException failed) {
            if (!allowManager) throw failed;
            System.getLogger(LaunchFiles.class.getName()).log(System.Logger.Level.ERROR, "无法准备所选模组，仅打开游戏启动器", failed);
            try {
                plan = buildPlan(game, payload, home, libs, cache, enabled, List.of());
            } catch (IOException managerFailure) {
                managerFailure.addSuppressed(failed);
                throw managerFailure;
            }
            plan.setProperty("plugin.count", "0");
            plan.setProperty("selection.problem", failed.toString());
        }
        Path dest = cache.resolve("launch.properties");
        Path tmp = Files.createTempFile(cache, "launch-", ".tmp");
        try {
            try (var out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) { plan.store(out, null); }
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
        System.out.printf("[Standalone] %s；游戏归档 %s，模组归档 %s。启动清单：%s%n",
                game.profile(), plan.getProperty("game.count"), plan.getProperty("mod.count"), dest);
        return dest;
    }

    private static Properties buildPlan(GameInstall game, Path payload, Path home, List<Path> libs, Path cache,
                                        List<String> enabled, List<ModCatalog.Archive> mods) throws IOException {
        var thirdParty = mods.stream().filter(mod -> !mod.modId().equals(ModCatalog.OPTIMIZER_ID)).toList();
        Map<Path, Path> archives = ArchiveProfile.mounts(home, cache, game, thirdParty, libs,
                thirdParty.size() == mods.size() ? null : payload);
        Properties plan = new Properties();
        plan.setProperty("profile", game.profile());
        plan.setProperty("namespace", ArchiveProfile.NAMESPACE);
        if (enabled != null) {
            plan.setProperty("enabled.count", Integer.toString(enabled.size()));
            for (int i = 0; i < enabled.size(); i++) plan.setProperty("enabled." + i, enabled.get(i));
        }
        List<Path> preparedGame = new ArrayList<>();
        for (Path path : game.gameJars()) {
            plan.setProperty("game.source." + preparedGame.size(), path.toString());
            preparedGame.add(archives.get(path));
        }
        paths(plan, "game", preparedGame); paths(plan, "library", libs);
        var preparedMods = new ArrayList<Path>();
        for (var mod : mods) {
            if (mod.modId().equals(ModCatalog.OPTIMIZER_ID)) {
                plan.setProperty("payload", payload.toString());
                plan.setProperty("payload.source", mod.path().toString());
                if (!mod.declaredPath().equals(mod.path())) plan.setProperty("payload.declared", mod.declaredPath().toString());
                continue;
            }
            plan.setProperty("mod.source." + preparedMods.size(), mod.path().toString());
            plan.setProperty("mod.root." + preparedMods.size(), mod.modRoot().toString());
            if (!mod.declaredPath().equals(mod.path())) plan.setProperty("mod.declared." + preparedMods.size(), mod.declaredPath().toString());
            preparedMods.add(archives.get(mod.path()));
        }
        paths(plan, "mod", preparedMods);
        return plan;
    }

    private static void paths(Properties p, String key, List<Path> paths) {
        p.setProperty(key + ".count", Integer.toString(paths.size()));
        for (int i = 0; i < paths.size(); i++) p.setProperty(key + "." + i, paths.get(i).toString());
    }
}
