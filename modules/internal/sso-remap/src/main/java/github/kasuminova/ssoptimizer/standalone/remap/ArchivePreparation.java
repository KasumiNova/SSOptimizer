package github.kasuminova.ssoptimizer.standalone.remap;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
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

/**
 * 在私有缓存目录生成 obf 游戏和模组归档的 named 副本，输入文件只读。
 * 可并发调用；同一进程和跨进程准备均互斥。失败以带来源的 IOException 报告，不发布不完整缓存。
 */
public final class ArchivePreparation {
    public static final String REMAP_ABI = "ssoptimizer-named-v1";
    private static final System.Logger LOG = System.getLogger(ArchivePreparation.class.getName());
    private static final int FORMAT = 3;
    private static final String TOOL = "sso-remap-1;TinyRemapper-0.14.0;mapping-io-0.8.0;ASM-9.10.1";

    private ArchivePreparation() {
    }

    /**
     * 同时转换按加载顺序给出的游戏与 obf 模组；hierarchyJars 只提供继承解析且保持顺序。
     * 返回规范化绝对输入路径到可挂载归档的不可变有序映射。
     * 无需过滤的 named 输入保留原路径；其余模组返回缓存副本，原始文件不被修改。
     * 缓存键包含输入、映射、依赖的内容与顺序、工具字节和 JVM 版本；命中仍校验全部输出摘要。
     */
    public static synchronized Map<Path, Path> prepare(final Path cacheRoot, final Path mapping,
                                                       final List<Path> gameJars, final List<Path> obfuscatedMods,
                                                       final List<Path> hierarchyJars) throws IOException {
        return prepare(cacheRoot, mapping, gameJars, obfuscatedMods, hierarchyJars, Set.of());
    }

    /** 按挂载顺序筛选所有模组；forcedNamedMods 是通过 mod_info.json 明确声明 named 的模组。返回路径包含过滤后的副本或未修改的 named 原件。 */
    public static synchronized Map<Path, Path> prepare(final Path cacheRoot, final Path mapping,
                                                       final List<Path> gameJars, final List<Path> orderedMods,
                                                       final List<Path> hierarchyJars, final Set<Path> forcedNamedMods) throws IOException {
        final Path root = cacheRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new PreparationException.Input("Cache root must not be a symbolic link: " + root);
        }
        Files.createDirectories(root);
        final Path lockPath = root.resolve("prepare.lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new PreparationException.Input("Cache lock must not be a symbolic link: " + lockPath);
        }
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = lockChannel.lock()) {
            return locked(root, mapping, gameJars, orderedMods, hierarchyJars, forcedNamedMods);
        } catch (PreparationException error) {
            LOG.log(System.Logger.Level.ERROR, error.getMessage(), error);
            throw error;
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.ERROR, "Archive preparation failed under " + root, error);
            throw new PreparationException.Input("Invalid archive or mapping under " + root + ": " + error.getMessage(), error);
        } catch (IOException error) {
            LOG.log(System.Logger.Level.ERROR, "Archive preparation failed under " + root, error);
            throw new PreparationException.Cache("Archive preparation failed under " + root, error);
        }
    }

    private static Map<Path, Path> locked(final Path root, final Path mapping, final List<Path> games,
                                          final List<Path> mods, final List<Path> hierarchy, Set<Path> forcedNamedMods) throws IOException {
        try (var paths = Files.newDirectoryStream(root, ".prepare-*")) {
            for (Path previous : paths) cleanWork(root, previous);
        }
        final List<Input> inputs = new ArrayList<>();
        final Set<Path> unique = new HashSet<>();
        final Set<Path> realPaths = new HashSet<>();
        add(inputs, unique, realPaths, mapping, "mapping");
        for (Path game : games) {
            add(inputs, unique, realPaths, game, "game");
        }
        for (Path dependency : hierarchy) {
            add(inputs, unique, realPaths, dependency, "hierarchy");
        }
        var forced = new HashSet<Path>();
        forcedNamedMods.forEach(path -> forced.add(path.toAbsolutePath().normalize()));
        for (Path mod : mods) {
            add(inputs, unique, realPaths, mod, "mod");
            Input input = inputs.getLast();
            if (forced.remove(input.path()) && !input.named()) {
                inputs.set(inputs.size() - 1, new Input(input.path(), input.role(), input.digest(), input.keyDigest(), true));
            }
        }
        if (!forced.isEmpty()) throw new PreparationException.Input("Declared named mod is not mounted: " + forced);
        if (games.isEmpty()) {
            throw new PreparationException.Input("At least one game archive is required");
        }
        final String key = key(inputs);
        final Path destination = root.resolve(key);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            try {
                final Map<Path, Path> cached = readCache(destination, key, inputs);
                verifyInputs(inputs);
                LOG.log(System.Logger.Level.INFO, "Using verified named cache " + destination);
                return cached;
            } catch (PreparationException.Input changed) {
                throw changed;
            } catch (IOException invalid) {
                LOG.log(System.Logger.Level.WARNING, "Invalid named cache " + destination + ": " + invalid.getMessage());
                if (!destination.getParent().equals(root) || !destination.getFileName().toString().equals(key)) {
                    throw new PreparationException.Cache("Unsafe cache quarantine target: " + destination, invalid);
                }
                Files.move(destination, root.resolve(key + ".invalid-" + UUID.randomUUID()), StandardCopyOption.ATOMIC_MOVE);
            }
        }
        final Path work = Files.createTempDirectory(root, ".prepare-");
        Files.writeString(work.resolve(".ssoptimizer-work"), "sso-remap/1", StandardOpenOption.CREATE_NEW);
        try {
            var rejected = new java.util.HashMap<ModClassFilter.Site,String>();
            for (int attempt = 0; ; attempt++) {
                try {
                    return publish(root, key, destination, Files.createDirectory(work.resolve("pass-" + attempt)), inputs, rejected);
                } catch (ModClassFilter.Unusable changed) {
                    int previous = rejected.size();
                    rejected.putAll(changed.entries);
                    if (previous == rejected.size()) throw new PreparationException.Conflict("Class selection did not progress", changed);
                    LOG.log(System.Logger.Level.WARNING, "模组类转换失败，将重新选择后续定义：" + changed.entries.size());
                }
            }
        } finally {
            cleanWork(root, work);
        }
    }

    private static Map<Path, Path> publish(final Path root, final String key, final Path destination,
                                           final Path work, final List<Input> inputs, Map<ModClassFilter.Site,String> rejected) throws IOException {
        final Path snapshots = Files.createDirectory(work.resolve("inputs"));
        final Path published = Files.createDirectory(work.resolve("named"));
        final List<ArchiveRemapper.Source> sources = new ArrayList<>();
        final List<Path> dependencySnapshots = new ArrayList<>();
        final List<Path> namedSnapshots = new ArrayList<>();
        final Map<Path,String> outputs = new LinkedHashMap<>();
        ModClassFilter filter = null;
        Path mappingSnapshot = null;
        for (int i = 0; i < inputs.size(); i++) {
            final Input input = inputs.get(i);
            final Path snapshot = snapshots.resolve(String.format(java.util.Locale.ROOT, "%04d-%s", i,
                    input.role().equals("mapping") ? "mapping.tiny" : "input.jar"));
            Files.copy(input.path(), snapshot);
            if (!Digests.file(snapshot).equals(input.digest())) {
                throw new PreparationException.Input("Input changed while snapshotting: " + input.path() + "; retry preparation");
            }
            switch (input.role()) {
                case "mapping" -> {
                    mappingSnapshot = snapshot;
                    filter = new ModClassFilter(MappingProjection.read(snapshot), rejected);
                }
                case "hierarchy" -> {
                    filter.reserve(input.path(), snapshot, true);
                    dependencySnapshots.add(snapshot);
                }
                default -> {
                    String outputName = String.format(java.util.Locale.ROOT, "%04d-%s.jar", outputs.size(), input.role());
                    Path selected = snapshot;
                    if (input.role().equals("game")) filter.reserve(input.path(), snapshot, input.named());
                    else selected = filter.select(input.path(), snapshot, input.named(), snapshots.resolve("selected-" + i + ".jar"));
                    if (input.named()) {
                        namedSnapshots.add(selected);
                        if (selected.equals(snapshot)) outputName = "";
                        else Files.copy(selected, published.resolve(outputName));
                    } else {
                        sources.add(new ArchiveRemapper.Source(input.path(), selected, input.role(), input.digest(), outputName));
                    }
                    outputs.put(input.path(), outputName);
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "Preparing " + sources.size() + " archives in " + work);
        try {
            if (!sources.isEmpty()) ArchiveRemapper.remap(mappingSnapshot, sources, dependencySnapshots, namedSnapshots, published, inputs.getFirst().keyDigest(), filter);
        } catch (ModClassFilter.Unusable changed) {
            throw changed;
        } catch (IOException error) {
            final String message = "Cannot prepare source archives "
                    + inputs.stream().filter(input -> !input.role().equals("mapping")).map(Input::path).toList()
                    + ": " + error.getMessage();
            if (error instanceof PreparationException.Conflict) {
                throw new PreparationException.Conflict(message, error);
            }
            throw new PreparationException.Input(message, error);
        }
        filter.writeReport(published);
        writeCache(published, key, outputs);
        verifyInputs(inputs);
        if (!published.toAbsolutePath().normalize().startsWith(work) || !destination.getParent().equals(root)) {
            throw new PreparationException.Cache("Unsafe cache publication path: " + published + " -> " + destination, null);
        }
        try {
            Files.move(published, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new PreparationException.Cache("Cache filesystem must support atomic directory publication: " + root, error);
        }
        LOG.log(System.Logger.Level.INFO, "Published named cache " + destination);
        return readCache(destination, key, inputs);
    }

    private static void cleanWork(final Path root, final Path work) {
        try {
            if (!work.getParent().equals(root) || !work.getFileName().toString().startsWith(".prepare-")
                    || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) return;
            Path marker = work.resolve(".ssoptimizer-work");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS) || Files.size(marker) != 11
                    || !Files.readString(marker).equals("sso-remap/1")) return;
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

    private static void add(final List<Input> inputs, final Set<Path> unique, final Set<Path> realPaths,
                            final Path path, final String role) throws IOException {
        final Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new PreparationException.Input("Missing " + role + " input: " + normalized);
        }
        if (!unique.add(normalized) || !realPaths.add(normalized.toRealPath())) {
            throw new PreparationException.Input("Duplicate input across preparation roles: " + normalized);
        }
        final String digest = Digests.file(normalized);
        final boolean named;
        try {
            named = (role.equals("game") || role.equals("mod")) && ArchiveEntries.isNamed(normalized);
        } catch (IOException error) {
            throw new PreparationException.Input("Cannot read archive " + normalized + ": " + error.getMessage(), error);
        }
        inputs.add(new Input(normalized, role, digest, role.equals("mapping") ? MappingProjection.digest(normalized) : digest, named));
    }

    private static String key(final List<Input> inputs) throws IOException {
        final MessageDigest hash = Digests.sha256();
        Digests.text(hash, TOOL);
        Digests.text(hash, Runtime.version().toString());
        final Set<Path> toolSources = new HashSet<>();
        for (Class<?> type : List.of(ArchivePreparation.class, net.fabricmc.tinyremapper.TinyRemapper.class,
                net.fabricmc.mappingio.tree.MemoryMappingTree.class, org.objectweb.asm.ClassReader.class,
                org.objectweb.asm.commons.ClassRemapper.class, org.objectweb.asm.tree.ClassNode.class,
                org.objectweb.asm.tree.analysis.Analyzer.class, org.objectweb.asm.util.CheckClassAdapter.class)) {
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
            Digests.text(hash, input.named() ? "named" : "source");
            Digests.text(hash, input.path().toString());
            Digests.text(hash, input.keyDigest());
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void verifyInputs(final List<Input> inputs) throws IOException {
        for (Input input : inputs) {
            if (!Files.isRegularFile(input.path()) || !Digests.file(input.path()).equals(input.digest())) {
                throw new PreparationException.Input("Input changed during preparation: " + input.path() + "; retry with a stable input set");
            }
        }
    }

    private static void writeCache(final Path directory, final String key, final Map<Path,String> outputs) throws IOException {
        final Path manifest = directory.resolve("complete.bin");
        try (var stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(manifest)))) {
            stream.writeInt(FORMAT);
            stream.writeUTF(key);
            stream.writeUTF(Digests.file(directory.resolve(ModClassFilter.REPORT)));
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

    private static Map<Path, Path> readCache(final Path directory, final String key, final List<Input> inputs) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) {
            throw new IOException("Invalid cache directory: " + directory);
        }
        final List<Input> expected = inputs.stream().filter(input -> input.role().equals("game") || input.role().equals("mod")).toList();
        final Map<Path, Path> result = new LinkedHashMap<>();
        try (var stream = new DataInputStream(new BufferedInputStream(Files.newInputStream(directory.resolve("complete.bin"))))) {
            final Path report = directory.resolve(ModClassFilter.REPORT);
            if (stream.readInt() != FORMAT || !stream.readUTF().equals(key) || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS)
                    || !stream.readUTF().equals(Digests.file(report)) || stream.readInt() != expected.size()) {
                throw new IOException("Cache manifest header mismatch: " + directory);
            }
            for (int i = 0; i < expected.size(); i++) {
                final String origin = stream.readUTF();
                final String name = stream.readUTF();
                if (!origin.equals(expected.get(i).path().toString())) throw new IOException("Cache origin mismatch: " + origin);
                if (name.isEmpty() && expected.get(i).named()) {
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
            var level = details.size() > 1 ? System.Logger.Level.WARNING : System.Logger.Level.INFO;
            LOG.log(level, details.getLast() + " 完整记录：" + report);
            details.stream().limit(Math.min(details.size() - 1, 3)).forEach(line -> LOG.log(level, line));
        }
        return Collections.unmodifiableMap(result);
    }

    private record Input(Path path, String role, String digest, String keyDigest, boolean named) {
    }
}
