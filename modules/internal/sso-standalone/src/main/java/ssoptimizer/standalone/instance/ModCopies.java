package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import static ssoptimizer.standalone.instance.InstanceFiles.NO_LINK;

final class ModCopies {
    private static final String ABSENT = "-";
    private static final String DIRECTORY = "directory";

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
        Properties changes = new Properties();
        var priorKeys = new TreeSet<>(previous.stringPropertyNames());
        priorKeys.remove("@game");
        var keys = new TreeSet<>(priorKeys);
        keys.addAll(source.keySet());
        for (String key : keys) {
            Path target = modFile(mods, key);
            String old = previous.getProperty(key, ABSENT);
            String desired = source.containsKey(key) ? (key.endsWith("/") ? DIRECTORY : InstanceFiles.digest(source.get(key))) : ABSENT;
            if (!desired.equals(ABSENT)) next.setProperty(key, desired);
            boolean official = key.startsWith("ssoptimizer/");
            if (old.equals(desired) && !official) continue;
            String installed = current(target);
            String previousChild = priorKeys.ceiling(key);
            boolean previouslyPopulated = previousChild != null && previousChild.startsWith(key);
            switch (decide(old, installed, desired, previouslyPopulated)) {
                case KEEP -> { }
                case UPDATE -> changes.setProperty(key, installed);
                case UNOWNED -> throw new IOException("实例目标已有非托管文件或目录：" + target);
                case CONFLICT -> throw new IOException("实例文件已被修改，请先备份并解决版本冲突：" + target);
            }
        }
        if (changes.isEmpty() && next.equals(previous)) return;
        Path staged = Files.createTempDirectory(state, "stage-");
        try {
            for (String key : changes.stringPropertyNames()) {
                if (!source.containsKey(key) || key.endsWith("/")) continue;
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
        recover(state, mods, game);
    }

    enum Change { KEEP, UPDATE, UNOWNED, CONFLICT }

    static Change decide(String previous, String installed, String desired, boolean previouslyPopulated) {
        if (previous.equals(ABSENT) && !installed.equals(ABSENT)) {
            return installed.equals(DIRECTORY) && desired.equals(DIRECTORY) && previouslyPopulated ? Change.KEEP : Change.UNOWNED;
        }
        if (installed.equals(desired)) return Change.KEEP;
        return installed.equals(previous) ? Change.UPDATE : Change.CONFLICT;
    }

    private static Path modFile(Path mods, String key) throws IOException {
        if (key.indexOf('/') < 1 || key.startsWith("coremods/")) throw new IOException("无效模组归属记录：" + key);
        return InstanceFiles.child(mods, key);
    }

    static void recover(Path state, Path mods, Path game) throws IOException {
        Path pending = InstanceFiles.child(state, "pending");
        if (!Files.exists(pending, NO_LINK)) return;
        Path inventory = InstanceFiles.child(pending, "next.properties");
        Path journal = InstanceFiles.child(pending, "changes.properties");
        InstanceFiles.regular(inventory);
        InstanceFiles.regular(journal);
        Properties next = read(inventory);
        if (!game.toString().equals(next.getProperty("@game"))) throw new IOException("实例更新日志的游戏来源不同：" + inventory);
        Properties changes = read(journal);
        var ordered = new java.util.ArrayList<>(changes.stringPropertyNames());
        ordered.sort((a, b) -> {
            boolean removeA = !next.containsKey(a), removeB = !next.containsKey(b);
            if (removeA != removeB) return removeA ? -1 : 1;
            return removeA ? b.compareTo(a) : a.compareTo(b);
        });
        for (String key : ordered) {
            Path target = modFile(mods, key);
            String desired = next.getProperty(key, ABSENT);
            String installed = current(target);
            if (installed.equals(desired)) continue;
            if (!installed.equals(changes.getProperty(key))) throw new IOException("恢复更新时目标已被修改：" + target);
            if (desired.equals(ABSENT)) {
                if (installed.equals(DIRECTORY)) {
                    try (var entries = Files.list(target)) { if (entries.findAny().isPresent()) continue; }
                }
                Files.delete(target);
            } else if (desired.equals(DIRECTORY)) {
                Files.createDirectory(target);
            } else {
                Path copy = InstanceFiles.child(pending, "files/" + key);
                if (!InstanceFiles.digest(copy).equals(desired)) throw new IOException("待更新文件摘要不符：" + copy);
                Files.createDirectories(target.getParent());
                replace(copy, target);
            }
        }
        Path enabled = InstanceFiles.child(mods, "enabled_mods.json");
        Path selection = InstanceFiles.child(pending, "enabled.json");
        if (Files.exists(selection, NO_LINK) && !Files.exists(enabled, NO_LINK)) writeNew(enabled, Files.readAllBytes(selection));
        replace(inventory, InstanceFiles.child(state, "installed.properties"));
        // 先移出恢复位置，删除中断后下次启动仍能读取完整归属清单。
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
