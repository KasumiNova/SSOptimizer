package ssoptimizer.standalone.launch;

import github.kasuminova.ssoptimizer.standalone.remap.ArchivePreparation;
import ssoptimizer.standalone.instance.InstanceLayout;
import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

public final class LaunchFiles {
    private static final String MAPPING = "76a5a926934f32fb2a4e75f4fdd61c7879f6f3a9e045dc926cd2873aa821d4b9";

    private LaunchFiles() {}

    static Path mapping(Path home) throws IOException {
        Path file = GameInstall.required(home.resolve("mappings/game-full.tiny.gz"));
        try (var in = new GZIPInputStream(Files.newInputStream(file))) {
            if (!MAPPING.equals(GameInstall.digest(in))) throw new IOException("发行映射与已验证的 RC8 named ABI 不一致：" + file);
        }
        return file;
    }

    public static Path validate(Path game, Path home, Path instance) throws IOException {
        DataLocation location = DataLocation.configured();
        return InstancePaths.validate(GameInstall.inspect(game.toRealPath(), location), home.toRealPath(), instance, location);
    }

    public static AutoCloseable lock(Path instance) throws IOException {
        Files.createDirectories(instance);
        Path path = instance.resolve("session.lock");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("实例已在使用，请退出该实例的游戏后重试：" + path);
            return channel;
        } catch (Throwable failure) {
            try { channel.close(); } catch (IOException e) { failure.addSuppressed(e); }
            throw failure;
        }
    }

    public static Path prepareForLaunch(Path gamePath, Path home, Path instance) throws Exception {
        DataLocation location = DataLocation.configured();
        return prepare(GameInstall.inspect(gamePath, location), home, instance, location, true);
    }

    static Path prepare(GameInstall game, Path home, Path instance, DataLocation location) throws Exception {
        return prepare(game, home, instance, location, false);
    }

    private static Path prepare(GameInstall game, Path home, Path instance, DataLocation location, boolean allowManager) throws Exception {
        Path payload = GameInstall.required(home.resolve("payload.jar"));
        Path mapping = mapping(home);
        Path dataRoot = location.root(game, instance);
        Path modsDir = dataRoot.resolve("mods");
        if (location == DataLocation.INSTANCE) {
            InstanceLayout.prepare(game.root(), home, instance);
        } else {
            for (String name : List.of("mods", "saves", "screenshots", "logs")) Files.createDirectories(dataRoot.resolve(name));
        }
        List<Path> libs = game.libraries(home);
        Path cache = instance.resolve("cache");
        PlatformFiles platform = PlatformFiles.inspect(game, home, cache);
        var hierarchy = new ArrayList<>(libs);
        hierarchy.add(platform.lwjgl());
        hierarchy.add(platform.lwjglUtil());
        List<String> enabled = null;
        Properties plan;
        try {
            enabled = ModCatalog.enabledIds(modsDir);
            List<ModCatalog.Archive> mods = ModCatalog.scan(modsDir, payload, enabled);
            plan = buildPlan(game, payload, mapping, libs, cache, hierarchy, enabled, mods);
            if (!ModCatalog.enabledIds(modsDir).equals(enabled)) throw new IOException("准备期间模组启用列表发生变化。请确认当前选择。");
        } catch (IOException failed) {
            if (!allowManager) throw failed;
            System.getLogger(LaunchFiles.class.getName()).log(System.Logger.Level.ERROR, "无法准备所选模组，仅打开游戏启动器", failed);
            try {
                plan = buildPlan(game, payload, mapping, libs, cache, hierarchy, enabled, List.of());
            } catch (IOException managerFailure) {
                managerFailure.addSuppressed(failed);
                throw managerFailure;
            }
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

    private static Properties buildPlan(GameInstall game, Path payload, Path mapping, List<Path> libs, Path cache,
                                        List<Path> baseHierarchy, List<String> enabled, List<ModCatalog.Archive> mods) throws IOException {
        var inputs = new ArrayList<Path>();
        var namedMods = new HashSet<Path>();
        var hierarchy = new ArrayList<>(baseHierarchy);
        for (var mod : mods) {
            if (mod.modId().equals(ModCatalog.OPTIMIZER_ID)) {
                hierarchy.add(payload);
                continue;
            }
            inputs.add(mod.path());
            if (mod.named()) namedMods.add(mod.path());
        }
        Map<Path,Path> outputs = ArchivePreparation.prepare(cache.resolve("named"), mapping, game.gameJars(), inputs, hierarchy, namedMods);
        Properties plan = new Properties();
        plan.setProperty("profile", game.profile());
        if (enabled != null) {
            plan.setProperty("enabled.count", Integer.toString(enabled.size()));
            for (int i = 0; i < enabled.size(); i++) plan.setProperty("enabled." + i, enabled.get(i));
        }
        List<Path> preparedGame = new ArrayList<>();
        for (Path path : game.gameJars()) preparedGame.add(output(outputs, path));
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
            if (!mod.declaredPath().equals(mod.path())) plan.setProperty("mod.declared." + preparedMods.size(), mod.declaredPath().toString());
            Path prepared = output(outputs, mod.path());
            preparedMods.add(ssoptimizer.standalone.mods.ModArchiveLayout.prepare(cache, mod, prepared));
        }
        paths(plan, "mod", preparedMods);
        return plan;
    }

    private static Path output(Map<Path,Path> outputs, Path path) throws IOException {
        Path result = outputs.get(path.toAbsolutePath().normalize());
        if (result == null) throw new IOException("预处理结果缺少归档：" + path);
        return GameInstall.required(result);
    }

    private static void paths(Properties p, String key, List<Path> paths) {
        p.setProperty(key + ".count", Integer.toString(paths.size()));
        for (int i = 0; i < paths.size(); i++) p.setProperty(key + "." + i, paths.get(i).toString());
    }
}
