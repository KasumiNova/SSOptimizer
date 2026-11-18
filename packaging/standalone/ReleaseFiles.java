import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

class ReleaseFiles {
    private static final String MAPPING = "76a5a926934f32fb2a4e75f4fdd61c7879f6f3a9e045dc926cd2873aa821d4b9";
    private static final String JDK_ZIP = "01c1c4cfe8c0e04a22a46d3cd1ddd18b9db9fb2a1b0a88ffd90e0aa57420845e";
    private static final String JDK_TREE = "85bc12ded64c03c04d272a424fb85384743c2361ee45eea009e5c92a144a16f8";
    private static final String JDK_PREFIX = "zulu25.38.201-ca-jdk25.0.5.1-win_x64/";
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "mapping" -> mapping(Path.of(args[1]), Path.of(args[2]));
            case "jdk" -> jdk(Path.of(args[1]), args[2], args[3], args[4], Path.of(args[5]), Boolean.parseBoolean(args[6]));
            case "audit" -> audit(Path.of(args[1]), args[2]);
            case "sign" -> sign(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], Path.of(args[5]));
            default -> throw new IllegalArgumentException(args[0]);
        }
    }

    private static MessageDigest digest() throws NoSuchAlgorithmException { return MessageDigest.getInstance("SHA-256"); }

    private static String sha(Path file) throws Exception {
        var hash = digest();
        try (var in = new DigestInputStream(Files.newInputStream(file), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
        return HEX.formatHex(hash.digest());
    }

    private static void mapping(Path source, Path output) throws Exception {
        Files.createDirectories(output.getParent());
        var tmp = Files.createTempFile(output.getParent(), "mapping-", ".tmp");
        try {
            var hash = digest();
            try (var raw = new BufferedInputStream(Files.newInputStream(source))) {
                raw.mark(2); boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset();
                try (var in = new DigestInputStream(gzip ? new GZIPInputStream(raw) : raw, hash);
                     var out = new GZIPOutputStream(Files.newOutputStream(tmp))) { in.transferTo(out); }
            }
            if (!HEX.formatHex(hash.digest()).equals(MAPPING)) throw new IOException("映射与 RC8 named ABI 不符：" + source);
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static SortedMap<String, String> inventory(Path root) throws Exception {
        SortedMap<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (var file : paths.toList()) {
                if (Files.isSymbolicLink(file)) throw new IOException("发行目录包含符号链接：" + file);
                if (Files.isDirectory(file)) continue;
                if (!Files.isRegularFile(file)) throw new IOException("发行条目类型无效：" + file);
                files.put(root.relativize(file).toString().replace(File.separatorChar, '/'), sha(file));
            }
        }
        return files;
    }

    private static String entries(SortedMap<String, String> files) {
        var result = new StringBuilder();
        files.forEach((name, hash) -> result.append(hash).append("  ").append(name).append('\n'));
        return result.toString();
    }

    private static void checkJdk(Path home) throws Exception {
        String hash = HEX.formatHex(digest().digest(entries(inventory(home)).getBytes(StandardCharsets.UTF_8)));
        if (!JDK_TREE.equals(hash)) throw new IOException("固定 Windows x64 JDK 的文件或许可摘要不符：" + home + "，实际 " + hash);
    }

    private static void download(String url, Path target) throws Exception {
        if (!"https".equalsIgnoreCase(URI.create(url).getScheme())) throw new IOException("JDK 镜像必须使用 HTTPS");
        Files.createDirectories(target.getParent());
        var tmp = Files.createTempFile(target.getParent(), "jdk-", ".part");
        try {
            var response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL).build().send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(15)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200 || !sha(tmp).equals(JDK_ZIP)) throw new IOException("Windows JDK 下载失败或 SHA-256 不符：" + url);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static void jdk(Path output, String localHome, String localZip, String url, Path cache, boolean offline) throws Exception {
        Files.createDirectories(output.getParent());
        var temp = Files.createTempDirectory(output.getParent(), "jdk-");
        try {
            if (!localHome.isEmpty()) {
                Path home = Path.of(localHome); checkJdk(home);
                try (var paths = Files.walk(home)) {
                    for (var p : paths.filter(Files::isRegularFile).toList()) {
                        Path to = temp.resolve(home.relativize(p)); Files.createDirectories(to.getParent()); Files.copy(p, to);
                    }
                }
            } else {
                Path archive = localZip.isEmpty() ? cache : Path.of(localZip);
                if (localZip.isEmpty() && !Files.exists(cache) && !offline) download(url, cache);
                if (!Files.isRegularFile(archive) || !sha(archive).equals(JDK_ZIP)) throw new IOException("固定 JDK ZIP 缺失或 SHA-256 不符：" + archive);
                try (var zip = new ZipFile(archive.toFile())) {
                    for (var entry : Collections.list(zip.entries())) {
                        String name = entry.getName();
                        if (!name.startsWith(JDK_PREFIX)) throw new IOException("JDK ZIP 根目录无效：" + name);
                        var dest = temp.resolve(name.substring(JDK_PREFIX.length())).normalize();
                        if (!dest.startsWith(temp)) throw new IOException("JDK ZIP 条目越界：" + name);
                        if (entry.isDirectory()) { Files.createDirectories(dest); continue; }
                        Files.createDirectories(dest.getParent());
                        try (var in = zip.getInputStream(entry)) { Files.copy(in, dest); }
                    }
                }
            }
            checkJdk(temp);
            if (Files.isDirectory(output, LinkOption.NOFOLLOW_LINKS)) {
                boolean empty;
                try (var children = Files.list(output)) { empty = children.findAny().isEmpty(); }
                if (empty) Files.delete(output);
            }
            if (Files.exists(output)) checkJdk(output); else Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temp)) try (var paths = Files.walk(temp)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }

    private static void audit(Path home, String platform) throws Exception {
        var archives = new ArrayList<>(List.of(home.resolve("agent.jar"), home.resolve("payload.jar")));
        for (String dir : List.of("lib", "game-lib")) try (var jars = Files.list(home.resolve(dir))) { archives.addAll(jars.toList()); }
        var owners = new HashMap<String, Path>();
        for (var path : archives) {
            try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                if (path.equals(home.resolve("agent.jar"))) {
                    var attrs = jar.getManifest().getMainAttributes();
                    for (var attr : List.of("Premain-Class", "Main-Class")) {
                        String type = attrs.getValue(attr);
                        if (type == null || jar.getJarEntry(type.replace('.', '/') + ".class") == null) throw new IOException("agent 缺少 " + attr);
                    }
                }
                if (jar.stream().anyMatch(e -> e.getName().toLowerCase(Locale.ROOT).endsWith(".jar"))) {
                    throw new IOException("发行组件中含有未声明的嵌套 jar：" + path);
                }
                for (var e : jar.versionedStream().filter(en -> en.getName().endsWith(".class")).toList()) {
                    String name = e.getName();
                    if (name.startsWith("com/fs/") || name.startsWith("org/lwjgl/") || name.startsWith("io/github/nanoforged/")) throw new IOException("发行包含游戏或 NanoForge 类：" + path + ":" + name);
                    if (name.startsWith("com/thoughtworks/xstream/")) throw new IOException("XStream 必须来自游戏安装：" + path + ":" + name);
                    if (name.startsWith("org/objectweb/asm/") || name.startsWith("org/apache/log4j/") || name.startsWith("org/apache/logging/log4j/") || name.startsWith("org/slf4j/") || name.startsWith("org/xmlpull/")) {
                        boolean gameClass = name.startsWith("org/xmlpull/");
                        String expected = gameClass ? "game-lib" : "lib";
                        if (!path.getParent().equals(home.resolve(expected))) throw new IOException("类所在加载域错误：" + path + ":" + name + "，应在 " + expected);
                        Path other = owners.putIfAbsent(name, path);
                        if (other != null) throw new IOException("重复类：" + name + "，" + path + " 和 " + other);
                    }
                }
            }
        }
        for (String mod : List.of("font", "ime", "render", "loading", "texcompress")) {
            boolean windows = platform.equals("windows");
            var p = home.resolve("native/" + platform + "/" + (windows ? "ssoptimizer_" + mod + ".dll" : "libssoptimizer_" + mod + ".so"));
            try (var bin = new RandomAccessFile(p.toFile(), "r")) {
                if (windows) {
                    if (bin.readUnsignedShort() != 0x4d5a) throw new IOException("无效的 PE 文件：" + p);
                    bin.seek(0x3c); int offset = Integer.reverseBytes(bin.readInt()); bin.seek(offset);
                    if (bin.readInt() != 0x50450000 || bin.readUnsignedShort() != 0x6486) throw new IOException("Windows native 需要 PE x64：" + p);
                } else if (bin.readInt() != 0x7f454c46 || bin.read() != 2) throw new IOException("Linux native 需要 ELF64：" + p);
            }
        }
    }

    private static void sign(Path version, Path signed, Path templates, String id, Path keyFile) throws Exception {
        Path secret = keyFile.toRealPath();
        Files.createDirectories(signed);
        if (secret.startsWith(version.toRealPath()) || secret.startsWith(signed.toRealPath()) || secret.startsWith(templates.toRealPath())) {
            throw new IOException("私钥位于待发布目录中：" + secret);
        }
        var bytes = Files.readAllBytes(secret);
        String keyHash = HEX.formatHex(digest().digest(bytes));
        if (bytes.length > 0 && bytes[0] == '-') bytes = Base64.getMimeDecoder().decode(Files.readString(keyFile).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
        var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        if (key.getModulus().bitLength() < 3072) throw new GeneralSecurityException("发行私钥要求 RSA 3072 位或以上");
        var tools = signed.resolve("version/tools"); Files.createDirectories(tools);
        var b64 = Base64.getEncoder();
        var modulus = key.getModulus().toByteArray(); if (modulus[0] == 0) modulus = Arrays.copyOfRange(modulus, 1, modulus.length);
        String manager = Files.readString(templates.resolve("Manage.ps1")).replace("@MODULUS@", b64.encodeToString(modulus))
                .replace("@EXPONENT@", b64.encodeToString(key.getPublicExponent().toByteArray()));
        Files.writeString(tools.resolve("Manage.ps1"), "\ufeff" + manager);
        for (String name : List.of("Manage.cmd", "Launch.cmd")) Files.copy(templates.resolve(name), tools.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(templates.resolve("Install.cmd"), signed.resolve("Install.cmd"), StandardCopyOption.REPLACE_EXISTING);
        var files = inventory(version);
        for (String name : List.of("Manage.ps1", "Manage.cmd", "Launch.cmd")) files.put("tools/" + name, sha(tools.resolve(name)));
        if (files.containsValue(keyHash) || files.containsValue(HEX.formatHex(digest().digest(key.getEncoded())))) {
            throw new IOException("版本文件中含有签名私钥的副本或硬链接");
        }
        var manifest = ("SSOptimizer-Windows-x64-v1\n" + id + "\n" + entries(files)).getBytes(StandardCharsets.UTF_8);
        var sig = Signature.getInstance("SHA256withRSA"); sig.initSign(key); sig.update(manifest);
        Files.write(signed.resolve("version/release.manifest"), manifest); Files.write(signed.resolve("version/release.sig"), sig.sign());
    }
}
