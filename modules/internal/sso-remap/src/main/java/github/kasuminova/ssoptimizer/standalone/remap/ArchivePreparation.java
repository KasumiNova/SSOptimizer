package github.kasuminova.ssoptimizer.standalone.remap;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 将指定的 obf 输入转换到 named 命名空间，已声明为 named 的输入保留原件。 */
public final class ArchivePreparation {
    public static final String REMAP_ABI = "ssoptimizer-named-v1";
    private static final String CACHE_FORMAT = "declared-named-3/tiny-remapper-0.14.0/mapping-io-0.8.0/asm-9.10.1";

    private ArchivePreparation() {}

    public static Map<Path, Path> prepare(Path cacheRoot, Path mapping, List<Path> games,
                                          List<Path> mods, List<Path> hierarchy) throws IOException {
        return prepare(cacheRoot, mapping, games, mods, hierarchy, Set.of());
    }

    /** 只转换 games 和 mods 指定的归档，hierarchy 用于解析继承。缓存按所用输入的内容、位置和加载顺序区分。 */
    public static synchronized Map<Path, Path> prepare(Path cacheRoot, Path mapping, List<Path> games,
                                                       List<Path> mods, List<Path> hierarchy, Set<Path> namedMods) throws IOException {
        Path root = Files.createDirectories(cacheRoot.toAbsolutePath().normalize());
        var sources = new ArrayList<ArchiveRemapper.Source>();
        var order = new ArrayList<Path>();
        var named = new ArrayList<Path>();
        for (boolean mod : List.of(false, true)) {
            for (Path input : mod ? mods : games) {
                Path path = input.toAbsolutePath().normalize();
                order.add(path);
                if (namedMods.contains(input) || "named".equals(ArchiveEntries.attributes(path).getValue(ArchiveEntries.NAMESPACE))) {
                    named.add(path);
                } else {
                    sources.add(new ArchiveRemapper.Source(path, mod, order.size() + ".jar"));
                }
            }
        }
        var mounts = new LinkedHashMap<Path, Path>();
        order.forEach(path -> mounts.put(path, path));
        if (sources.isEmpty()) return Collections.unmodifiableMap(mounts);
        MessageDigest key = Digests.sha256();
        Digests.text(key, CACHE_FORMAT);
        Digests.text(key, Runtime.version().toString());
        fingerprint(key, mapping);
        Digests.text(key, "hierarchy:" + hierarchy.size());
        for (Path path : hierarchy) fingerprint(key, path);
        Digests.text(key, "inputs:" + order.size());
        for (Path path : order) fingerprint(key, path);
        for (var source : sources) {
            Digests.text(key, source.outputName());
            Digests.text(key, source.mod() ? "mod" : "game");
        }
        Path destination = root.resolve(HexFormat.of().formatHex(key.digest()));
        sources.forEach(source -> mounts.put(source.original(), destination.resolve(source.outputName())));
        try (var channel = FileChannel.open(root.resolve("prepare.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (!Files.isDirectory(destination)) {
                Path work = Files.createTempDirectory(root, "prepare-");
                try {
                    Path output = Files.createDirectory(work.resolve("named"));
                    ArchiveRemapper.remap(mapping, sources, hierarchy, named, order, output);
                    Files.move(output, destination, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    try (var tree = Files.walk(work)) {
                        for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(mounts);
    }

    private static void fingerprint(MessageDigest key, Path path) throws IOException {
        Digests.text(key, path.toAbsolutePath().normalize().toString());
        try (var input = Files.newInputStream(path);
             var content = new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), Digests.sha256())) {
            input.transferTo(content);
            key.update(content.getMessageDigest().digest());
        }
    }
}
