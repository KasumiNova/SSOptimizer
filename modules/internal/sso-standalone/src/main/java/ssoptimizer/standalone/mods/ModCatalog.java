package ssoptimizer.standalone.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

    private record Mod(String id, String name, String sortKey, Path dir, JsonObject info) {}

    private ModCatalog() {}

    public static final String OPTIMIZER_ID = "ssoptimizer";
    private static final System.Logger LOG = System.getLogger(ModCatalog.class.getName());

    /** 只收集启用的游戏模组；declaredPath 保留游戏声明的拼写。 */
    public static List<Archive> scan(Path modsDir, Path payload, List<String> enabledIds) throws IOException {
        return scan(modsDir, payload, enabledIds, "named");
    }

    public static List<Archive> scan(Path modsDir, Path payload, List<String> enabledIds, String namespace) throws IOException {
        Path root = modsDir.toAbsolutePath().normalize();
        Set<String> enabled = new LinkedHashSet<>(enabledIds);
        if (enabled.isEmpty()) return List.of();
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
        var seen = new ArchiveIdentities();
        for (Mod mod : mods.reversed()) {
            String where = mod.dir.resolve("mod_info.json") + " [" + mod.id + "]";
            checkNanoForgeRequirement(mod.info, where);
            boolean own = mod.id.equals(OPTIMIZER_ID);
            JsonArray jars = mod.info.has("jars") ? array(mod.info.get("jars"), where + ": jars") : new JsonArray();
            if (own && jars.size() != 1) throw new IOException(where + " 必须声明一份 SSOptimizer payload");
            String source = mod.info.has("ssoptimizerNamespace")
                    ? ModJar.namespace(string(mod.info.get("ssoptimizerNamespace"), where + ": ssoptimizerNamespace"), where) : null;
            if ("obf".equals(namespace) && "named".equals(source)) throw new IOException(where + " 声明了 named 模组，请使用作者发布的 obf 版本。");
            String plugin = mod.info.has("modPlugin") ? string(mod.info.get("modPlugin"), where + ": modPlugin") : null;
            for (JsonElement item : jars) {
                Path raw = declaredPath(mod.dir, string(item, where + ": jars"));
                Path declared = raw.normalize();
                Path path = jarPath(mod.dir, declared);
                if (!raw.equals(declared) && Files.exists(raw) && !Files.isSameFile(raw, path)) {
                    throw new IOException("模组 jar 声明路径与规范化路径指向不同文件：" + raw + " -> " + raw.toRealPath()
                            + "，规范化后：" + path.toRealPath());
                }
                if (!path.equals(declared)) System.getLogger(ModCatalog.class.getName()).log(System.Logger.Level.WARNING,
                        "模组 {0} 的 jar 路径大小写不匹配：声明 {1}，实际 {2}", mod.id, declared, path);
                boolean payloadFile = Files.isSameFile(path, payload);
                if (!(own && payloadFile) && !path.toRealPath().startsWith(mod.dir.toRealPath())) {
                    throw new IOException(where + " 的 jar 指向模组目录之外：" + path);
                }
                boolean samePayload = payloadFile || Files.size(path) == Files.size(payload)
                        && Files.mismatch(path, payload) == -1;
                if (own != samePayload) throw new IOException(where + " 的 jar 与发布 payload 冲突：" + path + "、" + payload);
                boolean named = ModJar.inspect(path, mod.id, source, own, plugin, namespace);
                Archive archive = new Archive(mod.id, path, named, declared, mod.dir);
                var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                seen.add(archive, attrs.fileKey(), attrs.size());
                archives.add(archive);
            }
        }
        return List.copyOf(archives);
    }

    public static List<String> enabledIds(Path modsDir) throws IOException {
        Path file = modsDir.resolve("enabled_mods.json");
        if (Files.notExists(file)) return List.of();
        JsonArray list = array(GameJson.read(file).get("enabledMods"), file + ": enabledMods");
        var ids = new LinkedHashSet<String>();
        for (JsonElement item : list) {
            String id = id(item, file + ": enabledMods");
            if (!ids.add(id)) throw new IOException(file + " 重复启用模组：" + id);
        }
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
            Path raw = dir.resolve(entry);
            if (!raw.equals(path) && Files.exists(raw) && !raw.toRealPath().startsWith(dir.toRealPath())) {
                throw new IOException(dir + " 的 jar 路径经符号链接指向模组目录外：" + jar);
            }
            return raw;
        } catch (InvalidPathException e) {
            throw new IOException(dir + " 的 jar 路径无效：" + jar, e);
        }
    }

    static final class ArchiveIdentities {
        private final Map<Object, Archive> keyed = new HashMap<>();
        private final Map<Long, List<Archive>> allBySize = new HashMap<>();
        private final Map<Long, List<Archive>> unkeyedBySize = new HashMap<>();

        void add(Archive archive, Object key, long size) throws IOException {
            Archive previous = key == null ? null : keyed.get(key);
            if (previous == null) {
                var candidates = key == null ? allBySize : unkeyedBySize;
                for (Archive candidate : candidates.getOrDefault(size, List.of())) {
                    if (Files.isSameFile(archive.path(), candidate.path())) { previous = candidate; break; }
                }
            }
            if (previous != null) throw new IOException("重复声明同一归档：" + previous + "、" + archive);
            allBySize.computeIfAbsent(size, ignored -> new ArrayList<>()).add(archive);
            if (key == null) unkeyedBySize.computeIfAbsent(size, ignored -> new ArrayList<>()).add(archive);
            else keyed.put(key, archive);
        }
    }

    private static Path jarPath(Path mod, Path declared) throws IOException {
        Path base = mod.toRealPath();
        Path current = mod;
        for (Path component : mod.relativize(declared)) {
            if (!current.toRealPath().startsWith(base)) throw new IOException("jar 父目录指向模组外：" + current);
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

    private static void checkNanoForgeRequirement(JsonObject info, String where) throws IOException {
        if (!(info.get("dependencies") instanceof JsonArray dependencies)) return;
        for (JsonElement value : dependencies) {
            if (!(value instanceof JsonObject dependency) || !text(dependency.get("id")).equalsIgnoreCase("nanoforge")) continue;
            JsonElement required = dependency.get("required");
            if (required == null ? !booleanValue(dependency.get("optional"), false) : booleanValue(required, true)) {
                throw new IOException(where + " 硬依赖 NanoForge，请使用 NanoForge 启动或在模组管理器中取消勾选");
            }
        }
    }

    private static String text(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString() ? element.getAsString() : "";
    }

    private static boolean booleanValue(JsonElement element, boolean defaultValue) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean()
                ? element.getAsBoolean() : defaultValue;
    }
}
