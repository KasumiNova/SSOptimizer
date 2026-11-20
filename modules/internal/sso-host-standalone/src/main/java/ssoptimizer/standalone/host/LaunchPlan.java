package ssoptimizer.standalone.host;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

final class LaunchPlan {
    static final String PAYLOAD_PACKAGE = "github.kasuminova.ssoptimizer.";
    private static final List<String> SYSTEM = List.of("java.", "jdk.", "sun.", "javax.imageio.", "javax.sound.",
            "javax.swing.", "org.lwjgl.", "org.apache.log4j.", "org.apache.logging.", "org.slf4j.", "org.objectweb.asm.",
            "org.spongepowered.asm.", "net.minecraft.launchwrapper.", "com.gtnewhorizons.retrofuturabootstrap.",
            "ssoptimizer.standalone.host.", FlavorHost.SYSTEM_PACKAGE);
    final List<Path> game;
    final List<Path> archives;
    final List<Path> mods;
    final Set<Path> modRoots;
    final Set<String> enabled;
    final String selectionProblem;
    final Path payload;
    private final Map<Path, Path> prepared;
    final Map<Path, Path> origins;
    private final Map<Path, Path> declaredSources;
    final List<String> plugins;

    LaunchPlan(Properties p) throws IOException {
        var order = new LinkedHashSet<Path>();
        game = paths(p, "game");
        plugins = strings(p, "plugin");
        List<Path> libraries = paths(p, "library");
        mods = paths(p, "mod");
        selectionProblem = p.getProperty("selection.problem");
        enabled = p.containsKey("enabled.count") ? Set.copyOf(strings(p, "enabled")) : null;
        var replacements = new HashMap<Path, Path>();
        var sources = new HashMap<Path, Path>();
        var aliases = new HashMap<Path, Path>();
        for (int i = 0; i < game.size(); i++) {
            Path mounted = game.get(i);
            Path source = origin(p, "game", i, mounted);
            sources.put(mounted, source);
            sources.put(source, source);
            replacements.put(source, mounted);
            replacements.put(mounted, mounted);
        }
        payload = p.containsKey("payload") ? path(p, "payload") : null;
        if (payload != null) {
            replacements.put(payload, payload);
            Path source = path(p, "payload.source");
            replacements.put(source, payload);
            declared(p, "payload.declared", source, aliases);
            order.add(payload);
        }
        var roots = new HashSet<Path>();
        for (int i = 0; i < mods.size(); i++) {
            Path source = origin(p, "mod", i, mods.get(i));
            if ("obf".equals(p.getProperty("namespace"))) {
                roots.add(path(p, "mod.root." + i));
            }
            Path named = mods.get(i);
            replacements.put(source, named);
            replacements.put(named, named);
            sources.put(named, source);
            sources.put(source, source);
            declared(p, "mod.declared." + i, source, aliases);
        }
        modRoots = Set.copyOf(roots);
        prepared = Map.copyOf(replacements);
        origins = Map.copyOf(sources);
        declaredSources = Map.copyOf(aliases);
        order.addAll(game);
        order.addAll(libraries);
        order.addAll(mods);
        archives = List.copyOf(order);
    }

    static boolean isSystem(String name) { return SYSTEM.stream().anyMatch(name::startsWith); }

    private static void declared(Properties p, String key, Path source, Map<Path, Path> aliases) {
        String value = p.getProperty(key);
        if (value != null) aliases.put(Path.of(value), source);
    }

    Path namedSource(Path source) throws IOException {
        try {
            return prepared.get(source.toRealPath());
        } catch (NoSuchFileException missing) {
            Path declared = declaredSources.get(source.toAbsolutePath().normalize());
            return declared == null ? null : prepared.get(declared);
        }
    }

    static LaunchPlan read() throws IOException {
        String value = System.getProperty("ssoptimizer.standalone.plan");
        if (value == null || value.isBlank()) throw new IOException("缺少 ssoptimizer.standalone.plan");
        var properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of(value), StandardCharsets.UTF_8)) { properties.load(reader); }
        return new LaunchPlan(properties);
    }

    private static List<String> strings(Properties p, String key) {
        int count = Integer.parseInt(p.getProperty(key + ".count", "0"));
        var result = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) result.add(p.getProperty(key + "." + i));
        return List.copyOf(result);
    }

    private static List<Path> paths(Properties properties, String key) throws IOException {
        int count = Integer.parseInt(properties.getProperty(key + ".count"));
        var result = new ArrayList<Path>();
        for (int i = 0; i < count; i++) result.add(path(properties, key + "." + i));
        return List.copyOf(result);
    }

    private static Path origin(Properties p, String key, int i, Path mounted) throws IOException {
        String declared = p.getProperty(key + "." + i);
        String original = p.getProperty(key + ".source." + i, declared);
        return original.equals(declared) ? mounted : Path.of(original).toRealPath();
    }

    private static Path path(Properties p, String key) throws IOException {
        return Path.of(p.getProperty(key)).toRealPath();
    }
}
