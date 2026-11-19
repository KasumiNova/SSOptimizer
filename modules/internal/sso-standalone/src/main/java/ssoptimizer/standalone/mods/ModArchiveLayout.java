package ssoptimizer.standalone.mods;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemLoopException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipException;

public final class ModArchiveLayout {
    private static final LinkOption NO_LINK = LinkOption.NOFOLLOW_LINKS;
    private static final System.Logger LOG = System.getLogger(ModArchiveLayout.class.getName());

    private static final String DIRECTORY = "directory";

    private ModArchiveLayout() {}

    public static Map<Path, Path> prepare(Path cache, Map<ModCatalog.Archive, Path> declared) throws IOException {
        if (declared.isEmpty()) return Map.of();
        Path root = cache.toRealPath();
        if (!root.equals(cache.toAbsolutePath().normalize())) throw new IOException("实例缓存路径重定向：" + cache);
        Path parent = Files.createDirectories(root.resolve("mod-archives-v1"));
        if (!parent.toRealPath().equals(parent)) throw new IOException("模组归档缓存路径重定向：" + parent);

        var groups = new LinkedHashMap<Path, Map<ModCatalog.Archive, Path>>();
        for (var entry : declared.entrySet()) {
            var archive = entry.getKey();
            groups.computeIfAbsent(archive.modRoot(), ignored -> new LinkedHashMap<>()).put(archive, entry.getValue());
        }
        var results = new HashMap<Path, Path>();
        for (var group : groups.entrySet()) {
            Path modRoot = group.getKey();
            var inputs = new TreeMap<Path, Path>();
            var siblings = new HashSet<Path>();
            for (var entry : group.getValue().entrySet()) {
                Path source = entry.getKey().path();
                if (source.equals(modRoot) || !source.normalize().equals(source) || !source.startsWith(modRoot)) {
                    throw new IOException("模组声明归档路径越界：" + source);
                }
                inputs.put(modRoot.relativize(source), entry.getValue());
                siblings.add(source.getParent());
            }
            for (Path dir : siblings) {
                try (var files = Files.list(dir)) {
                    for (Path file : files.toList()) {
                        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                        addFile(modRoot, file, inputs);
                    }
                }
            }
            var pending = new ArrayDeque<>(inputs.keySet());
            var manifests = collectManifestPaths(modRoot, inputs, pending);
            Path dir = snapshot(parent, modRoot, inputs, manifests);
            for (var archive : group.getValue().keySet()) {
                results.put(archive.path(), dir.resolve(modRoot.relativize(archive.path())));
            }
        }
        return Map.copyOf(results);
    }

    private static void addFile(Path modRoot, Path file, Map<Path, Path> inputs) throws IOException {
        Path relative = modRoot.relativize(file);
        if (inputs.containsKey(relative) || !Files.isRegularFile(file)) return;
        Path real = file.toRealPath();
        if (!real.startsWith(modRoot.toRealPath())) {
            LOG.log(System.Logger.Level.WARNING, "模组归档链接指向模组目录外，未复制：{0} -> {1}", file, real);
            return;
        }
        inputs.put(relative, real);
    }

    private static Map<Path, Manifest> collectManifestPaths(Path modRoot, Map<Path, Path> inputs,
                                                             ArrayDeque<Path> pending) throws IOException {
        var manifests = new HashMap<Path, Manifest>();
        var visited = new HashSet<Path>();
        var directories = new HashSet<Path>();
        Path realRoot = modRoot.toRealPath();
        while (!pending.isEmpty()) {
            Path relative = pending.removeFirst();
            if (!visited.add(relative)) continue;
            Path source = inputs.get(relative);
            try (var jar = new JarFile(source.toFile(), false)) {
                var manifest = jar.getManifest();
                if (manifest == null) continue;
                String references = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (references == null || references.isBlank()) continue;
                URI location = modRoot.resolve(relative).toUri();
                var classPath = new ArrayList<String>();
                boolean rootReferenced = false;
                for (String token : references.strip().split("\\s+")) {
                    URI resolved = manifestLocation(location, token);
                    Path target = resolved == null ? null : Path.of(resolved).normalize();
                    if (target == null || !target.startsWith(modRoot)) {
                        classPath.add(token);
                        continue;
                    }
                    if (resolved.getPath().endsWith("/")) {
                        if (target.equals(modRoot) && jar.stream().noneMatch(entry -> isSignature(entry.getName()))) {
                            classPath.add(realRoot.toUri().toASCIIString());
                            rootReferenced = true;
                            continue;
                        }
                        if (Files.isDirectory(target) && directories.add(target)) addDirectory(modRoot, realRoot, target, inputs);
                    } else {
                        addFile(modRoot, target, inputs);
                        Path dependency = modRoot.relativize(target);
                        if (inputs.get(dependency) != null) pending.addLast(dependency);
                    }
                    classPath.add(token);
                }
                if (rootReferenced) {
                    manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, String.join(" ", classPath));
                    manifests.put(relative, manifest);
                }
            } catch (ZipException invalidCompanion) {
                LOG.log(System.Logger.Level.WARNING, "无法读取归档的 manifest：" + source + "：" + invalidCompanion.getMessage());
            }
        }
        return manifests;
    }

    private static URI manifestLocation(URI location, String token) {
        try {
            URI reference = URI.create(token);
            if (reference.isAbsolute() || reference.getAuthority() != null
                    || reference.getQuery() != null || reference.getFragment() != null) return null;
            URI resolved = location.resolve(reference);
            Path.of(resolved);
            return resolved;
        } catch (IllegalArgumentException invalid) {
            LOG.log(System.Logger.Level.WARNING, "无效的归档 manifest 类路径：" + location + "：" + token);
            return null;
        }
    }

    private static void addDirectory(Path modRoot, Path realRoot, Path directory, Map<Path, Path> inputs) throws IOException {
        Files.walkFileTree(directory, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attributes) throws IOException {
                Path real = path.toRealPath();
                if (path.equals(modRoot)) return FileVisitResult.CONTINUE;
                if (real.equals(realRoot) || !real.startsWith(realRoot)) {
                    LOG.log(System.Logger.Level.WARNING, "跳过指向模组根目录或目录之外的类路径链接：{0} -> {1}", path, real);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                inputs.putIfAbsent(modRoot.relativize(path), null);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                if (attributes.isRegularFile()) addFile(modRoot, path, inputs);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFileFailed(Path path, IOException error) throws IOException {
                if (error instanceof FileSystemLoopException) {
                    LOG.log(System.Logger.Level.WARNING, "跳过构成目录循环的类路径链接：{0}", path);
                    return FileVisitResult.CONTINUE;
                }
                throw error;
            }
        });
    }

    private static Path snapshot(Path parent, Path modRoot, Map<Path, Path> inputs,
                                 Map<Path, Manifest> manifests) throws IOException {
        var hashes = new LinkedHashMap<Path, String>();
        MessageDigest key = sha256();
        key.update(modRoot.toString().getBytes(StandardCharsets.UTF_8));
        for (var entry : inputs.entrySet()) {
            Path path = entry.getKey();
            String hash = entry.getValue() == null ? DIRECTORY : digest(entry.getValue(), manifests.get(path));
            hashes.put(entry.getKey(), hash);
            key.update(("\0" + entry.getKey() + "\0" + hash).getBytes(StandardCharsets.UTF_8));
        }
        for (Path path : inputs.keySet()) {
            for (Path ancestor = path.getParent(); ancestor != null; ancestor = ancestor.getParent()) {
                hashes.putIfAbsent(ancestor, DIRECTORY);
            }
        }
        Path dir = parent.resolve(HexFormat.of().formatHex(key.digest()));
        if (Files.exists(dir, NO_LINK)) {
            verify(dir, hashes);
            return dir;
        }
        Path staging = Files.createTempDirectory(parent, "prepare-");
        try {
            for (var entry : inputs.entrySet()) {
                Path file = staging.resolve(entry.getKey());
                if (entry.getValue() == null) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    Manifest manifest = manifests.get(entry.getKey());
                    if (manifest == null) Files.copy(entry.getValue(), file);
                    else writeJar(entry.getValue(), manifest, Files.newOutputStream(file));
                }
            }
            verify(staging, hashes);
            Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging, NO_LINK)) {
                try (var tree = Files.walk(staging)) {
                    for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
        return dir;
    }

    private static void verify(Path dir, Map<Path, String> hashes) throws IOException {
        if (!Files.isDirectory(dir, NO_LINK)) throw new IOException("无效的模组归档缓存目录：" + dir);
        try (var entries = Files.walk(dir)) {
            if (entries.count() != hashes.size() + 1L) throw new IOException("模组归档缓存文件清单不符：" + dir);
        }
        for (var entry : hashes.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            String hash = entry.getValue();
            boolean validType = DIRECTORY.equals(hash) ? Files.isDirectory(file, NO_LINK) : Files.isRegularFile(file, NO_LINK);
            if (!validType || !file.toRealPath().equals(file) || !DIRECTORY.equals(hash) && !digest(file).equals(hash)) {
                throw new IOException("模组归档已变化或缓存损坏：" + file);
            }
        }
    }

    private static String digest(Path file) throws IOException {
        return digest(file, null);
    }

    private static String digest(Path file, Manifest manifest) throws IOException {
        MessageDigest hash = sha256();
        if (manifest == null) {
            try (var in = new DigestInputStream(Files.newInputStream(file), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
        } else {
            writeJar(file, manifest, new DigestOutputStream(OutputStream.nullOutputStream(), hash));
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static void writeJar(Path source, Manifest manifest, OutputStream output) throws IOException {
        try (output; var jar = new JarFile(source.toFile(), false); var out = new JarOutputStream(output)) {
            JarEntry entry = new JarEntry(JarFile.MANIFEST_NAME);
            entry.setTime(0);
            out.putNextEntry(entry);
            manifest.write(out);
            out.closeEntry();
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var original = entries.nextElement();
                String name = original.getName();
                if (JarFile.MANIFEST_NAME.equalsIgnoreCase(name)) continue;
                JarEntry copy = new JarEntry(name);
                copy.setTime(Math.max(0, original.getTime()));
                out.putNextEntry(copy);
                try (var in = jar.getInputStream(original)) { in.transferTo(out); }
                out.closeEntry();
            }
        }
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/") || upper.indexOf('/', "META-INF/".length()) >= 0) return false;
        return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
