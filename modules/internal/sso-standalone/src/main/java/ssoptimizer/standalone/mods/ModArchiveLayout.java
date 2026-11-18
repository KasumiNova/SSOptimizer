package ssoptimizer.standalone.mods;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarFile;

public final class ModArchiveLayout {
    private static final String LAUNCHER = "aitweaks-launcher.jar";
    private static final String CORE = "aitweaks-core.jar";
    private static final String ASM = "asm-9.1.jar";
    private static final String DEV = "aitweaks-core-dev";
    private static final String BOOTSTRAP = "com/genir/aitweaks/launcher/loading/CoreLoaderManager$BootstrapLoader.class";

    private ModArchiveLayout() {}

    /** 返回要挂入 Launch 的归档。AITweaks 的 core 和 ASM 保留在该归档同级，仅由模组自身读取。 */
    public static Path prepare(Path cache, ModCatalog.Archive archive, Path preparedJar) throws IOException {
        if (!archive.modId().equals("aitweaks")) return preparedJar;
        try (var jar = new JarFile(preparedJar.toFile(), false)) {
            if (jar.getJarEntry(BOOTSTRAP) == null) return preparedJar;
            var manifest = jar.getManifest();
            if (manifest != null) {
                String classPath = manifest.getMainAttributes().getValue("Class-Path");
                if (classPath != null && !classPath.isBlank()) throw new IOException("AITweaks launcher 声明了额外共享类路径：" + preparedJar + "：" + classPath);
            }
        }
        Path source = archive.path().getParent().toRealPath();
        Path core;
        // 上游按文件名字典序选择最新 dev core。固定本次选择，避免其 getPath/removePrefix 在含空格路径和 Linux 上选错归档。
        try (var files = Files.list(source)) {
            core = files.filter(p -> p.getFileName().toString().contains(DEV))
                    .max(java.util.Comparator.comparing(p -> p.getFileName().toString())).orElse(source.resolve(CORE));
        }
        var inputs = new LinkedHashMap<String, Path>();
        inputs.put(LAUNCHER, preparedJar);
        inputs.put(ASM, sibling(source, source.resolve(ASM)));
        inputs.put(CORE, sibling(source, core));
        try (var asm = new JarFile(inputs.get(ASM).toFile(), false); var body = new JarFile(inputs.get(CORE).toFile(), false)) {
            if (asm.getJarEntry("org/objectweb/asm/ClassReader.class") == null) throw new IOException("无效的 AITweaks 私有 ASM：" + inputs.get(ASM));
            if (body.stream().noneMatch(e -> e.getName().startsWith("com/genir/aitweaks/core/") && e.getName().endsWith(".class"))) {
                throw new IOException("缺少 AITweaks core 类：" + core);
            }
        } catch (IOException e) {
            throw new IOException("AITweaks 私有归档不可用：" + core + "、" + inputs.get(ASM) + "：" + e.getMessage(), e);
        }
        var hashes = new LinkedHashMap<String, String>();
        MessageDigest key = sha256();
        for (var entry : inputs.entrySet()) {
            String hash = digest(entry.getValue());
            hashes.put(entry.getKey(), hash);
            key.update((entry.getKey() + "=" + hash + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        Path parent = cache.toAbsolutePath().normalize().resolve("aitweaks-v1");
        if (!parent.getParent().toRealPath().equals(parent.getParent())) throw new IOException("实例缓存路径重定向：" + cache);
        Files.createDirectories(parent);
        if (!parent.toRealPath().equals(parent)) throw new IOException("AITweaks 缓存目录重定向：" + parent);
        Path dir = parent.resolve(HexFormat.of().formatHex(key.digest()));
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            Path staging = Files.createTempDirectory(parent, "prepare-");
            try {
                for (var entry : inputs.entrySet()) Files.copy(entry.getValue(), staging.resolve(entry.getKey()));
                verify(staging, hashes);
                Files.move(staging, dir, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                    for (String name : inputs.keySet()) Files.deleteIfExists(staging.resolve(name));
                    Files.delete(staging);
                }
            }
        } else {
            verify(dir, hashes);
        }
        return dir.resolve(LAUNCHER);
    }

    static void checkDeclared(String modId, Path path) throws IOException {
        String name = path.getFileName().toString();
        if (modId.equals("aitweaks") && (name.equals(CORE) || name.equals(ASM) || name.contains(DEV))) {
            throw new IOException("AITweaks 私有归档不能在 mod_info.json 的 jars 中公开挂载：" + path);
        }
    }

    private static Path sibling(Path parent, Path path) throws IOException {
        if (!Files.isRegularFile(path) || !path.toRealPath().startsWith(parent)) throw new IOException("缺失或越界的 AITweaks 私有归档：" + path);
        return path;
    }

    private static void verify(Path dir, Map<String, String> hashes) throws IOException {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无效的 AITweaks 缓存目录：" + dir);
        try (var entries = Files.list(dir)) {
            if (entries.count() != hashes.size()) throw new IOException("AITweaks 缓存文件清单不符：" + dir);
        }
        for (var entry : hashes.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !digest(file).equals(entry.getValue())) {
                throw new IOException("AITweaks 归档已变化或缓存损坏，请移出缓存后重新准备：" + file);
            }
        }
    }

    private static String digest(Path file) throws IOException {
        MessageDigest hash = sha256();
        try (var in = new DigestInputStream(Files.newInputStream(file), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
