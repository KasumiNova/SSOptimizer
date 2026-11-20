package github.kasuminova.ssoptimizer.standalone.obf;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 按玩家游戏的加载顺序准备归档，只在缓存中保存修正后的字节。 */
public final class ObfArchives {
    private static final int FORMAT = 1;
    private static final String TOOL = "sso-obf/1";
    private static final System.Logger LOG = System.getLogger(ObfArchives.class.getName());
    private ObfArchives() {}

    /**
     * 返回规范化绝对输入路径到挂载路径的有序映射。合法且无需筛选的输入返回原件。
     * 游戏与预留库的定义优先于模组，模组间按传入顺序选择第一个有效定义。
     * 调用可跨线程与进程并发。签名校验、输入读取或缓存发布失败时抛出 IOException。
     */
    public static synchronized Map<Path, Path> prepare(Path cacheRoot, List<Path> gameJars,
                                                        List<Path> orderedMods, List<Path> reservedLibraries) throws IOException {
        Path root = cacheRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) throw new IOException("缓存目录为符号链接 " + root);
        Files.createDirectories(root);
        Path lockPath = root.resolve("prepare.lock");
        try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
             var lock = channel.lock()) {
            List<Input> inputs = new ArrayList<>();
            Set<Path> unique = new HashSet<>();
            add(inputs, unique, reservedLibraries, "library");
            add(inputs, unique, gameJars, "game");
            add(inputs, unique, orderedMods, "mod");
            String key = key(inputs);
            Path destination = root.resolve(key);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                try { return readCache(destination, key, inputs, true); }
                catch (IOException broken) {
                    LOG.log(System.Logger.Level.WARNING, "缓存校验失败 " + destination, broken);
                    Files.move(destination, root.resolve(key + ".invalid-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
                }
            }
            try (var dirs = Files.newDirectoryStream(root, ".prepare-*")) {
                for (Path old : dirs) cleanWork(root, old);
            }
            Path work = Files.createTempDirectory(root, ".prepare-");
            Files.writeString(work.resolve(".ssoptimizer-work"), "sso-obf/001", StandardOpenOption.CREATE_NEW);
            try {
                Path published = Files.createDirectory(work.resolve("output"));
                var outputs = new LinkedHashMap<Path,String>();
                var members = new GameMembers();
                for (Path game : gameJars) members.index(game);
                var selection = new JarSelection(members);
                for (Input input : inputs) {
                    if (input.role.equals("library")) {
                        selection.reserve(input.path);
                        continue;
                    }
                    Path snapshot = Files.createTempFile(work, "source-", ".jar");
                    Files.copy(input.path, snapshot, StandardCopyOption.REPLACE_EXISTING);
                    if (!Digests.file(snapshot).equals(input.digest)) throw new IOException("读取期间输入已改变 " + input.path);
                    String name = String.format(java.util.Locale.ROOT, "%04d-%s.jar", outputs.size(), input.role);
                    boolean changed = selection.prepare(input.path, snapshot, published.resolve(name), input.role.equals("mod"));
                    outputs.put(input.path, changed ? name : "");
                }
                selection.writeReport(published);
                writeCache(published, key, outputs);
                readCache(published, key, inputs, false);
                verifyInputs(inputs);
                Files.move(published, destination, StandardCopyOption.ATOMIC_MOVE);
                return readCache(destination, key, inputs, true);
            } finally { cleanWork(root, work); }
        }
    }

    private static void add(List<Input> inputs, Set<Path> unique, List<Path> paths, String role) throws IOException {
        for (Path raw : paths) {
            Path path = raw.toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !unique.add(path.toRealPath())) throw new IOException("输入缺失或重复 " + path);
            inputs.add(new Input(path, role, Digests.file(path)));
        }
    }

    private static void cleanWork(final Path root, final Path work) {
        try {
            if (!work.getParent().equals(root) || !work.getFileName().toString().startsWith(".prepare-")
                    || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) return;
            Path marker = work.resolve(".ssoptimizer-work");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 11
                    || !Files.readString(marker).equals("sso-obf/001")) return;
            try (var files = Files.walk(work)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    if (!file.normalize().startsWith(work)) throw new IOException("Invalid temporary path: " + file);
                    Files.delete(file);
                }
            }
        } catch (IOException error) {
            LOG.log(System.Logger.Level.WARNING, "Temporary files remain in " + work, error);
        }
    }

    private static String key(final List<Input> inputs) throws IOException {
        final MessageDigest hash = Digests.sha256();
        Digests.text(hash, TOOL);
        Digests.text(hash, Runtime.version().toString());
        final Set<Path> toolSources = new HashSet<>();
        for (Class<?> type : List.of(ObfArchives.class, org.objectweb.asm.ClassReader.class,
                org.objectweb.asm.commons.ClassRemapper.class, org.objectweb.asm.tree.ClassNode.class,
                org.objectweb.asm.tree.analysis.Analyzer.class)) {
            final var source = type.getProtectionDomain().getCodeSource();
            if (source == null) {
                throw new IOException("Cannot fingerprint remapper code source: " + type.getName());
            }
            final Path location;
            try {
                location = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            } catch (java.net.URISyntaxException | IllegalArgumentException error) {
                throw new IOException("Unsupported remapper code source: " + source.getLocation(), error);
            }
            if (!toolSources.add(location)) {
                continue;
            }
            if (Files.isDirectory(location)) {
                try (var files = Files.walk(location)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                        Digests.text(hash, location.relativize(file).toString());
                        Digests.text(hash, Digests.file(file));
                    }
                }
            } else {
                Digests.text(hash, Digests.file(location));
            }
        }
        for (Input input : inputs) {
            Digests.text(hash, input.role());

            Digests.text(hash, input.path().toString());
            Digests.text(hash, input.digest());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void verifyInputs(final List<Input> inputs) throws IOException {
        for (Input input : inputs) {
            if (!Files.isRegularFile(input.path()) || !Digests.file(input.path()).equals(input.digest())) {
                throw new IOException("Input changed during preparation: " + input.path() + "; retry with a stable input set");
            }
        }
    }

    private static void writeCache(final Path directory, final String key, final Map<Path,String> outputs) throws IOException {
        final Path manifest = directory.resolve("complete.bin");
        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(manifest)))) {
            stream.writeInt(FORMAT);
            stream.writeUTF(key);
            stream.writeUTF(Digests.file(directory.resolve(JarSelection.REPORT)));
            stream.writeInt(outputs.size());
            for (var entry : outputs.entrySet()) {
                stream.writeUTF(entry.getKey().toString());
                stream.writeUTF(entry.getValue());
                if (entry.getValue().isEmpty()) continue;
                final Path archive = directory.resolve(entry.getValue());
                try (FileChannel channel = FileChannel.open(archive, StandardOpenOption.WRITE)) {
                    channel.force(true);
                }
                stream.writeUTF(Digests.file(archive));
            }
        }
        try (FileChannel channel = FileChannel.open(manifest, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static Map<Path, Path> readCache(final Path directory, final String key, final List<Input> inputs, boolean logReport) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(directory.resolve("complete.bin"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Invalid cache directory: " + directory);
        }
        final List<Input> expected = inputs.stream().filter(input -> input.role().equals("game") || input.role().equals("mod")).toList();
        final Map<Path, Path> result = new LinkedHashMap<>();
        try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("complete.bin"))))) {
            final Path report = directory.resolve(JarSelection.REPORT);
            if (stream.readInt() != FORMAT || !stream.readUTF().equals(key) || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                    || !stream.readUTF().equals(Digests.file(report)) || stream.readInt() != expected.size()) {
                throw new IOException("Cache manifest header mismatch: " + directory);
            }
            for (int i = 0; i < expected.size(); i++) {
                final String origin = stream.readUTF();
                final String name = stream.readUTF();
                if (!origin.equals(expected.get(i).path().toString())) throw new IOException("Cache origin mismatch: " + origin);
                if (name.isEmpty()) {
                    result.put(expected.get(i).path(), expected.get(i).path());
                    continue;
                }
                final String digest = stream.readUTF();
                if (!name.equals(String.format(java.util.Locale.ROOT, "%04d-%s.jar", i, expected.get(i).role()))) {
                    throw new IOException("Cache source/order mismatch at " + i + " in " + directory);
                }
                final Path archive = directory.resolve(name);
                if (Files.isSymbolicLink(archive) || !Files.isRegularFile(archive) || !Digests.file(archive).equals(digest)) {
                    throw new IOException("Cache output missing or digest mismatch: " + archive);
                }
                result.put(expected.get(i).path(), archive);
            }
            if (stream.read() != -1) {
                throw new IOException("Trailing cache manifest data: " + directory);
            }
            var details = Files.readAllLines(report);
            if (details.isEmpty()) throw new IOException("Missing class filter summary: " + report);
            if (logReport) {
                var level = details.size() > 1 ? System.Logger.Level.WARNING : System.Logger.Level.INFO;
                LOG.log(level, details.getLast() + " 完整记录：" + report);
                details.stream().limit(Math.min(details.size() - 1, 3)).forEach(line -> LOG.log(level, line));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private record Input(Path path, String role, String digest) {
    }
}
