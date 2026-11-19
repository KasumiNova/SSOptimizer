package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static ssoptimizer.standalone.instance.InstanceFiles.NO_LINK;

final class ModCopies {
    private static final String ABSENT = "-";
    private static final String DIRECTORY = "directory";
    private static final String LOCAL = "local";

    private ModCopies() {}

    static Properties read(Path file) throws IOException {
        Properties values = new Properties();
        if (Files.exists(file, NO_LINK)) {
            InstanceFiles.regular(file);
            try (var in = Files.newInputStream(file)) { values.load(in); }
        }
        return values;
    }

    private static void store(Path file, Properties values) throws IOException {
        try (var out = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) { values.store(out, null); }
    }

    static void writeNew(Path file, byte[] data) throws IOException {
        Path temp = Files.createTempFile(file.getParent(), ".layout-", ".tmp");
        try {
            Files.write(temp, data);
            Files.move(temp, file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String current(Path path) throws IOException {
        if (!Files.exists(path, NO_LINK)) return ABSENT;
        if (Files.isDirectory(path, NO_LINK)) return DIRECTORY;
        InstanceFiles.regular(path);
        return InstanceFiles.digest(path);
    }

    static void update(Path state, Path mods, Path game, Map<String, Path> source, Properties previous, byte[] initialSelection) throws IOException {
        Properties next = new Properties();
        next.setProperty("@game", game.toString());
        boolean sourceChanged = previous.containsKey("@game") && !next.getProperty("@game").equals(previous.getProperty("@game"));
        Properties changes = new Properties();
        var keys = new TreeSet<String>();
        Set<String> retained = new HashSet<>();
        for (String key : previous.stringPropertyNames()) if (!key.equals("@game")) keys.add(fileKey(key));
        for (String key : source.keySet()) keys.add(fileKey(key));
        for (String key : keys) {
            Path target = modFile(mods, key);
            if (underRetained(key, retained)) continue;
            String old = recorded(previous, key);
            boolean official = official(key);
            if (LOCAL.equals(old) || sourceChanged && !official && !ABSENT.equals(old)) {
                record(next, key, LOCAL);
                retained.add(key);
                continue;
            }
            Path input = source.get(key);
            String desired = input != null ? InstanceFiles.digest(input) : source.containsKey(key + '/') ? DIRECTORY : ABSENT;
            if (old.equals(desired) && !DIRECTORY.equals(old) && !official) {
                record(next, key, desired);
                continue;
            }
            String installed = current(target);
            switch (decide(old, installed, desired, official)) {
                case KEEP -> record(next, key, desired);
                case UPDATE -> {
                    record(next, key, desired);
                    changes.setProperty(key, installed);
                }
                case PRESERVE -> {
                    record(next, key, LOCAL);
                    retained.add(key);
                }
                case UNOWNED -> throw new IOException("实例目标已有非托管文件或目录：" + target);
                case CONFLICT -> throw new IOException("实例 SSOptimizer 发行文件与当前版本冲突：" + target);
            }
        }
        if (changes.isEmpty() && next.equals(previous) && initialSelection == null) return;
        Path staged = Files.createTempDirectory(state, "stage-");
        try {
            for (String key : changes.stringPropertyNames()) {
                if (!source.containsKey(key)) continue;
                Path copy = staged.resolve("files").resolve(key);
                Files.createDirectories(copy.getParent());
                InstanceFiles.copyOwned(source.get(key), copy);
                if (!InstanceFiles.digest(copy).equals(next.getProperty(key))) throw new IOException("复制期间源文件已更改：" + source.get(key));
            }
            if (initialSelection != null) Files.write(staged.resolve("enabled.json"), initialSelection, StandardOpenOption.CREATE_NEW);
            store(staged.resolve("next.properties"), next);
            store(staged.resolve("changes.properties"), changes);
            Files.move(staged, state.resolve("pending"), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staged, NO_LINK)) InstanceFiles.deletePrivateTree(staged);
        }
        recover(state, mods);
    }

    enum Change { KEEP, UPDATE, PRESERVE, UNOWNED, CONFLICT }

    static Change decide(String previous, String installed, String desired, boolean official) {
        if (installed.equals(previous)) {
            if (installed.equals(desired)) return Change.KEEP;
            if (isTypeChange(installed, desired)) return official ? Change.CONFLICT : Change.PRESERVE;
            return Change.UPDATE;
        }
        if (!official) return Change.PRESERVE;
        if (DIRECTORY.equals(installed) && DIRECTORY.equals(desired)) return Change.KEEP;
        if (previous.equals(ABSENT)) return Change.UNOWNED;
        if (installed.equals(desired)) return Change.KEEP;
        return Change.CONFLICT;
    }

    private static boolean isTypeChange(String installed, String desired) {
        return !ABSENT.equals(installed) && !ABSENT.equals(desired) && DIRECTORY.equals(installed) != DIRECTORY.equals(desired);
    }

    private static String fileKey(String entry) {
        return entry.endsWith("/") ? entry.substring(0, entry.length() - 1) : entry;
    }

    private static String recorded(Properties entries, String key) {
        return entries.getProperty(key, entries.getProperty(key + '/', ABSENT));
    }

    private static void record(Properties entries, String key, String value) {
        entries.remove(key);
        entries.remove(key + '/');
        if (!ABSENT.equals(value)) entries.setProperty(DIRECTORY.equals(value) ? key + '/' : key, value);
    }

    private static boolean underRetained(String key, Set<String> retained) {
        for (int slash = key.indexOf('/'); slash >= 0; slash = key.indexOf('/', slash + 1)) {
            if (retained.contains(key.substring(0, slash))) return true;
        }
        return false;
    }

    private static boolean official(String key) {
        return switch (key) {
            case "ssoptimizer", "ssoptimizer/mod_info.json", "ssoptimizer/jars", "ssoptimizer/jars/payload.jar" -> true;
            default -> false;
        };
    }

    private static Path modFile(Path mods, String key) throws IOException {
        int slash = key.indexOf('/');
        String root = slash < 0 ? key : key.substring(0, slash);
        if (root.equalsIgnoreCase("coremods") || root.equalsIgnoreCase("enabled_mods.json") || root.equals("@game")) {
            throw new IOException("无效模组归属记录：" + key);
        }
        return InstanceFiles.child(mods, key);
    }

    static void recover(Path state, Path mods) throws IOException {
        Path pending = InstanceFiles.child(state, "pending");
        if (!Files.exists(pending, NO_LINK)) return;
        Path inventory = InstanceFiles.child(pending, "next.properties");
        Path journal = InstanceFiles.child(pending, "changes.properties");
        InstanceFiles.regular(inventory);
        InstanceFiles.regular(journal);
        Properties next = read(inventory);
        if (!next.containsKey("@game")) throw new IOException("实例更新日志缺少游戏来源：" + inventory);
        Properties changes = read(journal);
        var ordered = new ArrayList<>(changes.stringPropertyNames());
        ordered.sort((a, b) -> {
            boolean removeA = ABSENT.equals(recorded(next, fileKey(a))), removeB = ABSENT.equals(recorded(next, fileKey(b)));
            if (removeA != removeB) return removeA ? -1 : 1;
            return removeA ? b.compareTo(a) : a.compareTo(b);
        });
        var preserved = new HashSet<String>();
        for (String entry : ordered) {
            String key = fileKey(entry);
            Path target = modFile(mods, key);
            String desired = recorded(next, key);
            String installed = current(target);
            if (installed.equals(desired)) continue;
            if (!installed.equals(changes.getProperty(entry)) || underRetained(key, preserved)
                    || !Files.isDirectory(target.getParent(), NO_LINK)) {
                if (official(key)) throw new IOException("恢复更新时 SSOptimizer 发行文件已被修改：" + target);
                record(next, key, LOCAL);
                preserved.add(key);
                continue;
            }
            if (desired.equals(ABSENT)) {
                if (installed.equals(DIRECTORY)) {
                    try (var entries = Files.list(target)) {
                        if (entries.findAny().isPresent()) {
                            if (!official(key)) record(next, key, LOCAL);
                            continue;
                        }
                    }
                }
                Files.delete(target);
            } else if (desired.equals(DIRECTORY)) {
                Files.createDirectory(target);
            } else {
                Path copy = InstanceFiles.child(pending, "files/" + key);
                InstanceFiles.regular(copy);
                if (!InstanceFiles.digest(copy).equals(desired)) throw new IOException("待更新文件摘要不符：" + copy);
                replace(copy, target);
            }
        }
        Path enabled = InstanceFiles.child(mods, "enabled_mods.json");
        Path selection = InstanceFiles.child(pending, "enabled.json");
        if (Files.exists(selection, NO_LINK) && !Files.exists(enabled, NO_LINK)) {
            InstanceFiles.regular(selection);
            writeNew(enabled, Files.readAllBytes(selection));
        }
        Path registry = InstanceFiles.child(state, "installed.properties");
        Path temp = Files.createTempFile(state, ".layout-", ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) { next.store(out, null); }
            Files.move(temp, registry, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        Path done = state.resolve("done-" + java.util.UUID.randomUUID());
        Files.move(pending, done, StandardCopyOption.ATOMIC_MOVE);
        InstanceFiles.deletePrivateTree(done);
    }

    private static void replace(Path source, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), ".layout-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
