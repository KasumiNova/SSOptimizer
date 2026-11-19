package ssoptimizer.standalone.host;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

final class LaunchPlan {
    static final String PAYLOAD_PACKAGE = "github.kasuminova.ssoptimizer.";
    static final String REFLECTION_PACKAGE = PAYLOAD_PACKAGE + "standalone.remap.reflect.";
    private static final List<String> SYSTEM = List.of("java.", "jdk.", "sun.", "javax.imageio.", "javax.sound.",
            "javax.swing.", "org.lwjgl.", "org.apache.log4j.", "org.apache.logging.", "org.slf4j.", "org.objectweb.asm.",
            "org.spongepowered.asm.", "net.minecraft.launchwrapper.", "com.gtnewhorizons.retrofuturabootstrap.",
            "ssoptimizer.standalone.host.", REFLECTION_PACKAGE);
    final List<Path> game;
    final List<Path> archives;
    final List<Path> mods;
    final Set<String> enabled;
    final boolean fixedSelection;
    final Path payload;
    final boolean hasGameModPayload;
    private final Map<Path, Path> prepared;
    private final Map<Path, Path> declaredSources;
    final Map<String, Path> owners;
    final Set<String> childPackages;

    LaunchPlan(Properties p) throws IOException {
        var order = new LinkedHashSet<Path>();
        game = paths(p, "game");
        if (game.isEmpty()) throw new IOException("启动清单缺少游戏归档");
        payload = path(p, "payload");
        List<Path> libraries = paths(p, "library");
        mods = paths(p, "mod");
        var selected = new LinkedHashSet<String>();
        int enabledCount = count(p, "enabled");
        for (int i = 0; i < enabledCount; i++) {
            String id = p.getProperty("enabled." + i);
            if (id == null || id.isBlank() || !selected.add(id)) throw new IOException("无效或重复的模组 ID: " + id);
        }
        enabled = Set.copyOf(selected);
        fixedSelection = Boolean.parseBoolean(p.getProperty("selection.fixed"));
        var replacements = new HashMap<Path, Path>();
        var aliases = new HashMap<Path, Path>();
        replacements.put(payload, payload);
        hasGameModPayload = p.containsKey("payload.source");
        if (hasGameModPayload) {
            replacements.put(path(p, "payload.source"), payload);
            declared(p, "payload.declared", "payload.source", aliases);
        }
        for (int i = 0; i < mods.size(); i++) {
            Path source = path(p, "mod.source." + i);
            Path named = mods.get(i);
            if (replacements.putIfAbsent(source, named) != null) throw new IOException("重复模组来源: " + source);
            Path previous = replacements.putIfAbsent(named, named);
            if (previous != null && !previous.equals(named)) throw new IOException("模组来源冲突: " + named);
            declared(p, "mod.declared." + i, "mod.source." + i, aliases);
        }
        prepared = Map.copyOf(replacements);
        declaredSources = Map.copyOf(aliases);
        order.add(payload);
        order.addAll(game);
        order.addAll(libraries);
        order.addAll(mods);
        if (order.size() != 1 + game.size() + libraries.size() + mods.size()) {
            throw new IOException("同一归档被重复分配给 Launch 域");
        }
        archives = List.copyOf(order);
        var index = new HashMap<String, Path>();
        var packages = new HashSet<String>();
        for (Path path : archives) {
            try (JarFile jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                jar.versionedStream().map(entry -> entry.getName())
                        .filter(n -> n.endsWith(".class") && !n.startsWith("META-INF/") && !n.equals("module-info.class"))
                        .map(n -> n.substring(0, n.length() - 6).replace('/', '.')).filter(n -> !isSystem(n))
                        .forEach(name -> {
                            index.putIfAbsent(name, path);
                            int end = name.lastIndexOf('.');
                            packages.add(end < 0 ? name : name.substring(0, end + 1));
                        });
            }
        }
        owners = Map.copyOf(index);
        // RFB 以包前缀逐项比较；同属 Launch 的子包只需登记一次。
        var prefixes = new LinkedHashSet<String>();
        packages.stream().sorted().forEach(prefix -> {
            if (SYSTEM.stream().anyMatch(s -> s.startsWith(prefix))) return;
            if (prefixes.stream().noneMatch(prefix::startsWith)) prefixes.add(prefix);
        });
        childPackages = Set.copyOf(prefixes);
    }

    private static boolean isSystem(String name) { return SYSTEM.stream().anyMatch(name::startsWith); }

    private static void declared(Properties p, String key, String sourceKey, Map<Path, Path> aliases) throws IOException {
        String value = p.getProperty(key);
        if (value == null) return;
        Path alias = Path.of(value);
        if (!alias.isAbsolute() || !alias.normalize().equals(alias)
                || !value.toLowerCase(java.util.Locale.ROOT).equals(p.getProperty(sourceKey).toLowerCase(java.util.Locale.ROOT))) {
            throw new IOException("无效的模组声明路径：" + key + "=" + value);
        }
        if (aliases.putIfAbsent(alias, Path.of(p.getProperty(sourceKey))) != null) throw new IOException("重复的模组声明路径：" + alias);
    }

    Path namedSource(Path source) throws IOException {
        Path absolute = source.toAbsolutePath();
        if (Files.notExists(absolute, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            absolute = declaredSources.get(absolute.normalize());
            if (absolute == null) return null;
        }
        return prepared.get(absolute.toRealPath());
    }

    static LaunchPlan read() throws IOException {
        String value = System.getProperty("ssoptimizer.standalone.plan");
        if (value == null || value.isBlank()) throw new IOException("缺少 ssoptimizer.standalone.plan");
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(value), StandardCharsets.UTF_8)) { properties.load(reader); }
        return new LaunchPlan(properties);
    }

    private static int count(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key + ".count");
        int count;
        try { count = Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("无效的清单项: " + key + ".count=" + value, e); }
        if (count < 0) throw new IOException("清单数量为负数: " + key);
        return count;
    }

    private static List<Path> paths(Properties properties, String key) throws IOException {
        int count = count(properties, key);
        var result = new ArrayList<Path>();
        for (int i = 0; i < count; i++) result.add(path(properties, key + "." + i));
        return List.copyOf(result);
    }

    private static Path path(Properties p, String key) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IOException("清单缺少 " + key);
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isRegularFile(path)) throw new IOException("无效的清单归档 " + key + "=" + path);
        return path.toRealPath();
    }
}
