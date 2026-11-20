package ssoptimizer.standalone.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ssoptimizer.standalone.mods.GameJson.array;
import static ssoptimizer.standalone.mods.GameJson.id;
import static ssoptimizer.standalone.mods.GameJson.string;

public final class ModCatalog {
    public record Archive(String modId, Path path, boolean named, Path declaredPath, Path modRoot) {
        Archive(String modId, Path path, boolean named) { this(modId, path, named, path, path.getParent()); }
    }

    public record Selection(List<Archive> archives, List<String> plugins) {}

    private record Mod(String id, String name, String sortKey, Path dir, JsonObject info) {}

    private ModCatalog() {}

    public static final String OPTIMIZER_ID = "ssoptimizer";
    private static final System.Logger LOG = System.getLogger(ModCatalog.class.getName());

    /** 只收集启用的游戏模组；declaredPath 保留游戏声明的拼写。 */
    public static List<Archive> scan(Path modsDir, Path payload, List<String> enabledIds) throws IOException {
        return scan(modsDir, payload, enabledIds, "named");
    }

    public static List<Archive> scan(Path modsDir, Path payload, List<String> enabledIds, String namespace) throws IOException {
        return selection(modsDir, payload, enabledIds, namespace).archives();
    }

    public static Selection selection(Path modsDir, Path payload, List<String> enabledIds, String namespace) throws IOException {
        Path root = modsDir.toAbsolutePath().normalize();
        Set<String> enabled = new LinkedHashSet<>(enabledIds);
        if (enabled.isEmpty()) return new Selection(List.of(), List.of());
        Map<String, Mod> selected = new LinkedHashMap<>();
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve("mod_info.json");
                if (Files.notExists(file)) continue;
                JsonObject info;
                String id;
                try {
                    info = GameJson.read(file);
                    id = id(info.get("id"), file + ": id");
                } catch (IOException invalid) {
                    LOG.log(System.Logger.Level.WARNING, "无法识别模组元数据：" + file, invalid);
                    continue;
                }
                if (!enabled.contains(id)) continue;
                String name = string(info.get("name"), file + ": name");
                String sort = info.has("sortString") ? string(info.get("sortString"), file + ": sortString") : "";
                Mod mod = new Mod(id, name, sort.isEmpty() ? name : sort, dir, info);
                Mod other = selected.putIfAbsent(id, mod);
                if (other != null) throw new IOException("已启用 id 重复：" + id + "，目录：" + other.dir + "、" + dir);
            }
        }
        var missing = new LinkedHashSet<>(enabled);
        missing.removeAll(selected.keySet());
        if (!missing.isEmpty()) throw new IOException(root + " 缺少已启用的模组：" + missing);

        // RC8 先按名称，再按 sortString 稳定排序；完全相同时反向展开一级目录的发现顺序。
        var mods = new ArrayList<>(selected.values());
        mods.sort(Comparator.comparing(Mod::sortKey).thenComparing(Mod::name));
        var archives = new ArrayList<Archive>();
        var plugins = new ArrayList<String>();
        var seen = new LinkedHashSet<Path>();
        for (Mod mod : mods.reversed()) {
            String where = mod.dir.resolve("mod_info.json") + " [" + mod.id + "]";
            boolean own = mod.id.equals(OPTIMIZER_ID);
            JsonArray jars = mod.info.has("jars") ? array(mod.info.get("jars"), where + ": jars") : new JsonArray();
            String source = mod.info.has("ssoptimizerNamespace")
                    ? string(mod.info.get("ssoptimizerNamespace"), where + ": ssoptimizerNamespace") : null;
            if (mod.info.has("modPlugin")) plugins.add(string(mod.info.get("modPlugin"), where + ": modPlugin"));
            for (JsonElement item : jars) {
                Path declared = declaredPath(mod.dir, string(item, where + ": jars"));
                Path path = jarPath(mod.dir, declared);
                if (!path.equals(declared)) LOG.log(System.Logger.Level.WARNING,
                        "模组 {0} 的 jar 路径大小写不匹配：声明 {1}，实际 {2}", mod.id, declared, path);
                if (!seen.add(path)) continue;
                archives.add(new Archive(mod.id, path, "named".equals(own ? namespace : source), declared, mod.dir));
            }
        }
        return new Selection(List.copyOf(archives), List.copyOf(plugins));
    }

    public static List<String> enabledIds(Path modsDir) throws IOException {
        Path file = modsDir.resolve("enabled_mods.json");
        if (Files.notExists(file)) return List.of();
        JsonArray list = array(GameJson.read(file).get("enabledMods"), file + ": enabledMods");
        var ids = new LinkedHashSet<String>();
        for (JsonElement item : list) ids.add(id(item, file + ": enabledMods"));
        return List.copyOf(ids);
    }

    public static String modId(Path modDir) throws IOException {
        Path file = modDir.resolve("mod_info.json");
        return id(GameJson.read(file).get("id"), file + ": id");
    }

    private static Path declaredPath(Path dir, String jar) throws IOException {
        try {
            Path entry = Path.of(jar);
            Path path = dir.resolve(entry).normalize();
            if (entry.isAbsolute() || jar.matches("^[a-zA-Z]:.*") || jar.startsWith("\\") || !path.startsWith(dir)) {
                throw new IOException(dir + " 引用了模组目录外的路径：" + jar);
            }
            return path;
        } catch (InvalidPathException e) {
            throw new IOException(dir + " 的 jar 路径无效：" + jar, e);
        }
    }

    private static Path jarPath(Path mod, Path declared) throws IOException {
        if (Files.isRegularFile(declared)) return declared;
        Path current = mod;
        for (Path component : mod.relativize(declared)) {
            String name = component.toString();
            Path exact = current.resolve(name);
            if (Files.exists(exact, LinkOption.NOFOLLOW_LINKS)) {
                current = exact;
                continue;
            }
            String folded = name.toLowerCase(Locale.ROOT);
            try (var entries = Files.list(current)) {
                var matches = entries.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).equals(folded))
                        .sorted().toList();
                if (matches.isEmpty()) throw new IOException("模组声明的 jar 缺失：" + declared + "，在 " + current + " 中找不到 " + name);
                if (matches.size() > 1) throw new IOException("模组 jar 路径大小写不明确：" + declared + "，候选：" + matches);
                current = matches.getFirst();
            }
        }
        if (!Files.isRegularFile(current)) throw new IOException("模组声明的 jar 不是普通文件：" + current);
        return current;
    }
}
