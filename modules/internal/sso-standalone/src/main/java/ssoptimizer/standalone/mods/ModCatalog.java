package ssoptimizer.standalone.mods;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ssoptimizer.standalone.mods.GameJson.array;
import static ssoptimizer.standalone.mods.GameJson.id;
import static ssoptimizer.standalone.mods.GameJson.string;

public final class ModCatalog {
    public record Archive(String modId, Path path, boolean named) {}

    private record Mod(String id, String name, String sortKey, Path dir, JsonObject info) {}

    private ModCatalog() {}

    /** 路径保留游戏声明的绝对位置。ssoptimizer 的记录已经与 payload 校验，必须映射到同一挂载。 */
    public static List<Archive> scan(Path modsDir, Path payload) throws IOException {
        Path root = modsDir.toAbsolutePath().normalize();
        List<Path> coremods = externalCoremods(root);
        if (!coremods.isEmpty()) throw new IOException("实例含有 NanoForge 外置 coremod：" + coremods + "，请移出独立实例或改用 NanoForge 启动");
        Set<String> enabled = new LinkedHashSet<>(enabledIds(root));
        if (enabled.isEmpty()) return List.of();
        Map<String, Mod> selected = new LinkedHashMap<>();
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve("mod_info.json");
                if (Files.notExists(file)) continue;
                JsonObject info = GameJson.read(file);
                String id = id(info.get("id"), file + ": id");
                if (!enabled.contains(id)) continue;
                String name = string(info.get("name"), file + ": name");
                string(info.get("description"), file + ": description");
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
        var seen = new HashMap<Object, Archive>();
        for (Mod mod : mods.reversed()) {
            String where = mod.dir.resolve("mod_info.json") + " [" + mod.id + "]";
            checkRequirements(mod.info, enabled, where);
            boolean own = mod.id.equals("ssoptimizer");
            JsonArray jars = mod.info.has("jars") ? array(mod.info.get("jars"), where + ": jars") : new JsonArray();
            if (own && jars.size() != 1) throw new IOException(where + " 必须声明一份 SSOptimizer payload");
            String source = mod.info.has("ssoptimizerNamespace")
                    ? ModJar.namespace(string(mod.info.get("ssoptimizerNamespace"), where + ": ssoptimizerNamespace"), where) : null;
            String plugin = mod.info.has("modPlugin") ? string(mod.info.get("modPlugin"), where + ": modPlugin") : null;
            for (JsonElement item : jars) {
                Path path = jarPath(mod.dir, string(item, where + ": jars"));
                ModArchiveLayout.checkDeclared(mod.id, path);
                boolean samePayload = Files.isSameFile(path, payload) || Files.size(path) == Files.size(payload)
                        && Files.mismatch(path, payload) == -1;
                if (own != samePayload) throw new IOException(where + " 的 jar 与发布 payload 冲突：" + path + "、" + payload);
                if (!samePayload && !path.toRealPath().startsWith(mod.dir.toRealPath())) {
                    throw new IOException(where + " 的 jar 指向模组目录之外：" + path);
                }
                boolean named = ModJar.inspect(path, mod.id, source, own, plugin);
                Archive archive = new Archive(mod.id, path, named);
                var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                Object key = attrs.fileKey() != null ? attrs.fileKey() : path.toRealPath();
                Archive previous = seen.putIfAbsent(key, archive);
                if (previous != null) throw new IOException("重复声明同一归档：" + previous + "、" + archive);
                archives.add(archive);
            }
        }
        return List.copyOf(archives);
    }

    public static List<Path> externalCoremods(Path modsDir) throws IOException {
        return ModJar.externalCoremods(modsDir.toAbsolutePath().normalize().resolve("coremods"));
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

    private static Path jarPath(Path dir, String jar) throws IOException {
        try {
            Path entry = Path.of(jar);
            Path path = dir.resolve(entry).normalize();
            if (entry.isAbsolute() || jar.matches("^[a-zA-Z]:.*") || jar.startsWith("\\") || !path.startsWith(dir)) {
                throw new IOException(dir + " 引用了模组目录外的路径：" + jar);
            }
            if (!Files.isRegularFile(path)) throw new IOException("模组声明的 jar 缺失或不是文件：" + path);
            return path;
        } catch (InvalidPathException e) {
            throw new IOException(dir + " 的 jar 路径无效：" + jar, e);
        }
    }

    private static void checkRequirements(JsonObject info, Set<String> enabled, String where) throws IOException {
        for (String key : List.of("classLoader", "customClassLoader", "customLoader")) {
            JsonElement value = info.get(key);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && !value.getAsBoolean()) continue;
            throw new IOException(where + " 声明了不支持的自建加载器：" + key + "=" + value);
        }
        if (info.has("dependencies")) {
            for (JsonElement value : array(info.get("dependencies"), where + ": dependencies")) {
                if (!value.isJsonObject()) throw new IOException(where + ": dependencies 元素必须为含 id 的对象");
                JsonObject dep = value.getAsJsonObject();
                String id = id(dep.get("id"), where + ": dependencies.id");
                boolean optional = dep.has("optional") && flag(dep, "optional", where);
                boolean required = dep.has("required") ? flag(dep, "required", where) : !optional;
                if (dep.has("optional") && optional == required) throw new IOException(where + " 的依赖标记冲突：" + id);
                if (!required) continue;
                if (id.equalsIgnoreCase("nanoforge")) throw new IOException(where + " 硬依赖 NanoForge，请改用 NanoForge 启动");
                if (!enabled.contains(id)) throw new IOException(where + " 的必需依赖未启用：" + id);
            }
        }
        if (info.has("conflicts")) {
            for (JsonElement value : array(info.get("conflicts"), where + ": conflicts")) {
                String id = id(value.isJsonObject() ? value.getAsJsonObject().get("id") : value, where + ": conflicts");
                if (enabled.contains(id)) throw new IOException(where + " 与已启用模组冲突：" + id);
            }
        }
    }

    private static boolean flag(JsonObject object, String key, String where) throws IOException {
        JsonElement value = object.get(key);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        throw new IOException(where + ": dependencies." + key + " 必须是布尔值");
    }
}
