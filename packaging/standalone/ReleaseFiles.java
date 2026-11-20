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
            case "audit" -> audit(Path.of(args[1]), args[2], args[3], Path.of(args[4]), args[5]);
            case "sign" -> sign(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], Path.of(args[5]), args[6], Path.of(args[7]), args[8]);
            case "verify" -> verify(Path.of(args[1]), Path.of(args[2]), args[3], args[4], Path.of(args[5]));
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

    private static void auditObfTree(Path home, String platform, Path project, String symbolJar) throws Exception {
        boolean windows = platform.equals("windows");
        var allowed = new HashSet<>(List.of("agent.jar", "payload.jar", "abi.properties", "distribution.properties", "libraries.lock", "mod/mod_info.json",
                "lib/sso-host-standalone-obf.jar", "lib/" + symbolJar));
        Path lock = project.resolve("packaging/standalone/libraries-obf.lock");
        if (Files.mismatch(lock, home.resolve("libraries.lock")) != -1) throw new IOException("obf 依赖锁与构建输入不符");
        for (String row : Files.readAllLines(lock)) {
            String[] fields = row.split(" ");
            String file = URI.create(fields[3]).getPath();
            String relative = fields[0] + file.substring(file.lastIndexOf('/'));
            if (!sha(home.resolve(relative)).equals(fields[1])) throw new IOException("锁定依赖摘要不符：" + relative);
            allowed.add(relative);
        }
        for (String name : List.of("MiSans-LICENSE.txt", "MiSans-Regular.ttf", "Oxanium-Medium.ttf", "lte50549.ttf", "orbitron-black.ttf", "orbitron-bold.ttf",
                "orbitron-light.ttf", "orbitron-medium.ttf", "orbitron-regular.ttf", "orbitron-semibold.ttf")) {
            String relative = "mod/fonts/" + name;
            if (Files.mismatch(project.resolve("game-fonts/ttf").resolve(name), home.resolve(relative)) != -1) throw new IOException("字体或许可内容不符：" + relative);
            allowed.add(relative);
        }
        for (String module : List.of("font", "ime", "render", "loading", "texcompress")) {
            allowed.add("native/" + platform + "/" + (windows ? "ssoptimizer_" + module + ".dll" : "libssoptimizer_" + module + ".so"));
        }
        if (windows) checkJdk(home.resolve("runtime")); else allowed.add("launch.sh");
        var missing = new TreeSet<>(allowed);
        try (var paths = Files.walk(home)) {
            for (var path : paths.toList()) {
                if (Files.isSymbolicLink(path)) throw new IOException("obf 目录包含符号链接：" + path);
                if (Files.isDirectory(path)) continue;
                String name = home.relativize(path).toString().replace(File.separatorChar, '/');
                if (!Files.isRegularFile(path)) throw new IOException("obf 条目类型无效：" + name);
                if (windows && name.startsWith("runtime/")) continue;
                if (!missing.remove(name)) throw new IOException("obf 包含未声明文件：" + name);
                if (name.endsWith(".properties") || name.endsWith(".json") || name.endsWith(".sh")) auditResource(name, Files.readAllBytes(path));
            }
        }
        if (!missing.isEmpty()) throw new IOException("obf 缺少发行文件：" + missing);
    }

    private static void audit(Path home, String platform, String flavor, Path project, String symbolJar) throws Exception {
        boolean obf = flavor.equals("obf");
        String namespace = obf ? "obf" : "named";
        var distribution = new Properties();
        try (var in = Files.newBufferedReader(home.resolve("distribution.properties"))) { distribution.load(in); }
        if (!flavor.equals(distribution.getProperty("flavor")) || !namespace.equals(distribution.getProperty("namespace")) || distribution.getProperty("abi", "").isBlank()) {
            throw new IOException("发行元数据与构件不符：" + home);
        }
        if (obf) {
            String abi = distribution.getProperty("abi");
            if (!abi.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || Files.mismatch(project.resolve("packaging/abi/" + abi + ".properties"), home.resolve("abi.properties")) != -1) {
                throw new IOException("obf 发行 ABI 描述与构建输入不符：" + abi);
            }
            auditObfTree(home, platform, project, symbolJar);
            if (!Files.isRegularFile(home.resolve("abi.properties")) || Files.exists(home.resolve("mappings"))) throw new IOException("obf 需要 ABI 描述并排除映射目录");
        } else {
            try (var in = new GZIPInputStream(Files.newInputStream(home.resolve("mappings/game-full.tiny.gz")))) {
                var hash = digest(); new DigestInputStream(in, hash).transferTo(OutputStream.nullOutputStream());
                if (!MAPPING.equals(HEX.formatHex(hash.digest()))) throw new IOException("dev 映射 SHA-256 不符");
            }
        }
        var archives = new ArrayList<>(List.of(home.resolve("agent.jar"), home.resolve("payload.jar")));
        for (String dir : List.of("lib", "game-lib")) try (var jars = Files.list(home.resolve(dir))) { archives.addAll(jars.toList()); }
        var owners = new HashMap<String, Path>();
        for (var path : archives) {
            String filename = path.getFileName().toString();
            if (obf && (filename.startsWith("sso-remap-") || filename.startsWith("tiny-remapper-") || filename.startsWith("mapping-io-"))) throw new IOException("obf 含开发依赖：" + path);
            try (var jar = new JarFile(path.toFile(), true, ZipFile.OPEN_READ, Runtime.version())) {
                if (filename.equals("payload.jar") || filename.equals("agent.jar") || filename.startsWith("sso-host-standalone-")) {
                    if (jar.getManifest() == null || !namespace.equals(jar.getManifest().getMainAttributes().getValue("SSOptimizer-Namespace"))) {
                        throw new IOException("发行 JAR 命名空间不符：" + path);
                    }
                }
                if (obf) for (var entry : Collections.list(jar.entries())) {
                    if (entry.isDirectory()) continue;
                    try (var input = jar.getInputStream(entry)) { auditResource(entry.getName(), input.readAllBytes()); }
                }
                if (path.equals(home.resolve("agent.jar"))) {
                    var lock = jar.getJarEntry("ssoptimizer/standalone/libraries.lock");
                    if (lock == null) throw new IOException("启动器缺少依赖锁：" + path);
                    try (var embedded = jar.getInputStream(lock)) {
                        if (!Arrays.equals(embedded.readAllBytes(), Files.readAllBytes(home.resolve("libraries.lock")))) {
                            throw new IOException("启动器与发行目录使用不同的依赖锁：" + path);
                        }
                    }
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

    private static void auditResource(String name, byte[] data) throws IOException {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("refmap") && !lower.endsWith(".class") || lower.endsWith(".tiny") || lower.endsWith(".tiny.gz") || lower.endsWith(".tsrg") ||
                lower.endsWith(".srg") || lower.endsWith(".mapping") || lower.startsWith("github/kasuminova/ssoptimizer/standalone/remap/") ||
                lower.startsWith("net/fabricmc/tinyremapper/") || lower.startsWith("net/fabricmc/mappingio/")) throw new IOException("obf 包含开发资源：" + name);
        if (data.length >= 2 && data[0] == 31 && data[1] == (byte)139) {
            try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) { auditResource(name + "!gzip", in.readAllBytes()); }
        } else if (data.length >= 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
            throw new IOException("obf 资源中含未声明的嵌套归档：" + name);
        } else {
            String text = new String(data, StandardCharsets.ISO_8859_1);
            if (text.contains("tiny\t2\t0\t") || text.startsWith("v1\t") || lower.endsWith(".json") && text.contains("\"refmap\"")) {
                throw new IOException("obf 资源含命名对照或 refmap 引用：" + name);
            }
        }
    }

    private static void verify(Path archive, Path signed, String id, String flavor, Path publicKey) throws Exception {
        var key = KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.X509EncodedKeySpec(Files.readAllBytes(publicKey)));
        try (var zip = new ZipFile(archive.toFile())) {
            var manifest = zip.getInputStream(zip.getEntry("version/release.manifest")).readAllBytes();
            var signature = Signature.getInstance("SHA256withRSA"); signature.initVerify(key); signature.update(manifest);
            if (!signature.verify(zip.getInputStream(zip.getEntry("version/release.sig")).readAllBytes())) throw new IOException("ZIP 清单签名无效：" + archive);
            var lines = new String(manifest, StandardCharsets.UTF_8).lines().toList();
            if (!lines.subList(0, 3).equals(List.of("SSOptimizer-Windows-x64-v2", id, flavor))) throw new IOException("ZIP 版本或类型不符：" + archive);
            var expected = new HashMap<String, String>();
            for (String line : lines.subList(3, lines.size())) {
                if (!line.matches("[0-9a-f]{64}  .+") || expected.put("version/" + line.substring(66), line.substring(0, 64)) != null) throw new IOException("清单条目无效：" + line);
            }
            expected.put("Install.cmd", sha(signed.resolve("Install.cmd")));
            var names = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (var entry : Collections.list(zip.entries())) {
                String name = entry.getName();
                if (!names.add(name) || name.startsWith("/") || name.contains("\\") || Arrays.asList(name.split("/")).contains("..")) throw new IOException("ZIP 路径无效：" + name);
                if (entry.isDirectory() || name.equals("version/release.manifest") || name.equals("version/release.sig")) continue;
                var hash = digest();
                try (var in = new DigestInputStream(zip.getInputStream(entry), hash)) { in.transferTo(OutputStream.nullOutputStream()); }
                if (!HEX.formatHex(hash.digest()).equals(expected.remove(name))) throw new IOException("ZIP 条目摘要或路径不符：" + name);
            }
            if (!expected.isEmpty()) throw new IOException("ZIP 缺少条目：" + expected.keySet());
        }
    }

    private static void sign(Path version, Path signed, Path templates, String id, Path keyFile, String flavor, Path project, String symbolJar) throws Exception {
        audit(version, "windows", flavor, project, symbolJar);
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
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        Files.write(signed.resolve("public.der"), publicKey.getEncoded());
        var tools = signed.resolve("version/tools"); Files.createDirectories(tools);
        var b64 = Base64.getEncoder();
        var modulus = key.getModulus().toByteArray(); if (modulus[0] == 0) modulus = Arrays.copyOfRange(modulus, 1, modulus.length);
        String manager = Files.readString(templates.resolve("Manage.ps1")).replace("@MODULUS@", b64.encodeToString(modulus))
                .replace("@EXPONENT@", b64.encodeToString(key.getPublicExponent().toByteArray())).replace("@FLAVOR@", flavor);
        Files.writeString(tools.resolve("Manage.ps1"), "\ufeff" + manager);
        for (String name : List.of("Manage.cmd", "Launch.cmd")) Files.copy(templates.resolve(name), tools.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(templates.resolve("Install.cmd"), signed.resolve("Install.cmd"), StandardCopyOption.REPLACE_EXISTING);
        var files = inventory(version);
        for (String name : List.of("Manage.ps1", "Manage.cmd", "Launch.cmd")) files.put("tools/" + name, sha(tools.resolve(name)));
        if (files.containsValue(keyHash) || files.containsValue(HEX.formatHex(digest().digest(key.getEncoded())))) {
            throw new IOException("版本文件中含有签名私钥的副本或硬链接");
        }
        var manifest = ("SSOptimizer-Windows-x64-v2\n" + id + "\n" + flavor + "\n" + entries(files)).getBytes(StandardCharsets.UTF_8);
        var sig = Signature.getInstance("SHA256withRSA"); sig.initSign(key); sig.update(manifest);
        Files.write(signed.resolve("version/release.manifest"), manifest); Files.write(signed.resolve("version/release.sig"), sig.sign());
    }
}
