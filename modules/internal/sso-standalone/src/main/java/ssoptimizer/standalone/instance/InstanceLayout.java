package ssoptimizer.standalone.instance;

import ssoptimizer.standalone.mods.ModCatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static ssoptimizer.standalone.instance.InstanceFiles.NO_LINK;

public final class InstanceLayout {
    private static final System.Logger LOG = System.getLogger(InstanceLayout.class.getName());

    private InstanceLayout() {}

    public static void prepare(Path gameRoot, Path home, Path instance) throws IOException {
        Path game = gameRoot.toRealPath();
        Path version = home.toRealPath();
        Path root = InstanceFiles.prospective(instance);
        if (game.startsWith(root)) throw new IOException("实例不能覆盖游戏安装：" + root);
        for (String dir : List.of("mods", "saves", "logs", "screenshots", "starsector-core", "data", "native", "jre")) {
            InstanceFiles.separate(InstanceFiles.prospective(game.resolve(dir)), root);
        }
        InstanceFiles.separate(version, root);
        Files.createDirectories(root);
        InstanceLock.with(root, state -> {
            Path mods = InstanceFiles.directory(root, "mods");
            for (String dir : List.of("saves", "logs", "screenshots")) InstanceFiles.directory(root, dir);
            ModCopies.recover(state, mods);
            Path registry = InstanceFiles.child(state, "installed.properties");
            Properties previous = ModCopies.read(registry);
            Path source = game.resolve("mods");
            Map<String, Path> files = new LinkedHashMap<>();
            boolean sourceHasOptimizer = false;
            if (Files.exists(source, NO_LINK)) {
                try (var entries = Files.list(source)) {
                    for (Path entry : entries.sorted().toList()) {
                        String name = entry.getFileName().toString();
                        if (name.equalsIgnoreCase("coremods") || !Files.isDirectory(entry)
                                || !Files.isRegularFile(entry.resolve("mod_info.json"))) continue;
                        if (sourceOptimizer(entry)) {
                            sourceHasOptimizer = true;
                            continue;
                        }
                        if (name.equalsIgnoreCase("ssoptimizer")) throw new IOException("普通模组占用官方模组目录：" + entry);
                        collect(entry.toRealPath(), root, name, files);
                    }
                }
            }
            Path template = version.resolve("mod").toRealPath();
            if (!ModCatalog.modId(template).equals("ssoptimizer")) throw new IOException("发行模板模组 ID 错误：" + template);
            collect(template, root, "ssoptimizer", files);
            Path payload = version.resolve("payload.jar");
            InstanceFiles.regular(payload);
            files.putIfAbsent("ssoptimizer/jars/", template);
            Path bundled = files.put("ssoptimizer/jars/payload.jar", payload);
            if (bundled != null && Files.mismatch(bundled, payload) != -1) throw new IOException("模板与发行负载不同：" + bundled);
            Path enabled = InstanceFiles.child(mods, "enabled_mods.json");
            byte[] initialSelection = null;
            if (!previous.containsKey("@game") && !Files.exists(enabled, NO_LINK)) {
                Path sourceSelection = source.resolve("enabled_mods.json");
                if (Files.exists(sourceSelection, NO_LINK)) {
                    initialSelection = Files.readAllBytes(sourceSelection);
                } else if (!sourceHasOptimizer) {
                    initialSelection = "{\"enabledMods\":[\"ssoptimizer\"]}".getBytes(StandardCharsets.UTF_8);
                }
            }
            ModCopies.update(state, mods, game, files, previous, initialSelection);
            return null;
        });
    }

    private static boolean sourceOptimizer(Path entry) {
        try {
            return ModCatalog.modId(entry).equals("ssoptimizer");
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "源模组元数据无法解析：" + entry.resolve("mod_info.json"), e);
            return false;
        }
    }

    private static void collect(Path source, Path instance, String name, Map<String, Path> files) throws IOException {
        InstanceFiles.separate(instance, source);
        try (var walk = Files.walk(source, FileVisitOption.FOLLOW_LINKS)) {
            for (var it = walk.iterator(); it.hasNext();) {
                Path path = it.next();
                Path real = path.toRealPath();
                if (!real.startsWith(source)) throw new IOException("模组内链接指向外部：" + path + " -> " + real);
                String relative = source.relativize(path).toString().replace(java.io.File.separatorChar, '/');
                String key = relative.isEmpty() ? name : name + '/' + relative;
                if (Files.isDirectory(real)) key += '/';
                else InstanceFiles.regular(real);
                if (files.putIfAbsent(key, real) != null) throw new IOException("实例目标重复：" + key);
            }
        } catch (java.io.UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
