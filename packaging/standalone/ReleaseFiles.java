import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.*;
import java.util.zip.*;

class ReleaseFiles {
    private static final String JDK_PREFIX = "zulu25.38.201-ca-jdk25.0.5.1-win_x64/";
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "mapping" -> mapping(Path.of(args[1]), Path.of(args[2]));
            case "jdk" -> jdk(Path.of(args[1]), args[2], args[3], args[4], Path.of(args[5]), Boolean.parseBoolean(args[6]));
            case "sign" -> sign(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], Path.of(args[5]), args[6]);
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
            try (var raw = new BufferedInputStream(Files.newInputStream(source))) {
                raw.mark(2); boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b; raw.reset();
                try (var in = gzip ? new GZIPInputStream(raw) : raw;
                     var out = new GZIPOutputStream(Files.newOutputStream(tmp))) { in.transferTo(out); }
            }
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static SortedMap<String, String> inventory(Path root) throws Exception {
        SortedMap<String, String> files = new TreeMap<>();
        try (var entries = Files.list(root)) {
            for (Path entry : entries.filter(path -> !path.getFileName().toString().equals("work")).toList()) {
                try (var paths = Files.walk(entry)) {
                    for (var file : paths.toList()) {
                        if (Files.isSymbolicLink(file)) throw new IOException("发行目录包含符号链接：" + file);
                        if (Files.isDirectory(file)) continue;
                        if (!Files.isRegularFile(file)) throw new IOException("发行条目类型无效：" + file);
                        files.put(root.relativize(file).toString().replace(File.separatorChar, '/'), sha(file));
                    }
                }
            }
        }
        return files;
    }

    private static String entries(SortedMap<String, String> files) {
        var result = new StringBuilder();
        files.forEach((name, hash) -> result.append(hash).append("  ").append(name).append('\n'));
        return result.toString();
    }

    private static void download(String url, Path target) throws Exception {
        if (!"https".equalsIgnoreCase(URI.create(url).getScheme())) throw new IOException("JDK 镜像必须使用 HTTPS");
        Files.createDirectories(target.getParent());
        var tmp = Files.createTempFile(target.getParent(), "jdk-", ".part");
        try {
            var response = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL).build().send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(java.time.Duration.ofMinutes(15)).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
            if (response.statusCode() != 200) throw new IOException("Windows JDK 下载失败：" + url);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }

    private static void jdk(Path output, String localHome, String localZip, String url, Path cache, boolean offline) throws Exception {
        Files.createDirectories(output.getParent());
        var temp = Files.createTempDirectory(output.getParent(), "jdk-");
        try {
            if (!localHome.isEmpty()) {
                Path home = Path.of(localHome);
                try (var paths = Files.walk(home)) {
                    for (var p : paths.filter(Files::isRegularFile).toList()) {
                        Path to = temp.resolve(home.relativize(p)); Files.createDirectories(to.getParent()); Files.copy(p, to);
                    }
                }
            } else {
                Path archive = localZip.isEmpty() ? cache : Path.of(localZip);
                if (localZip.isEmpty() && !Files.exists(cache) && !offline) download(url, cache);
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

            if (Files.isDirectory(output)) {
                try (var paths = Files.walk(output)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
            Files.move(temp, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(temp)) try (var paths = Files.walk(temp)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
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

    private static void sign(Path version, Path signed, Path templates, String id, Path keyFile, String flavor) throws Exception {
        Path secret = keyFile.toRealPath();
        Files.createDirectories(signed);
        if (secret.startsWith(version.toRealPath()) || secret.startsWith(signed.toRealPath()) || secret.startsWith(templates.toRealPath())) {
            throw new IOException("私钥位于待发布目录中：" + secret);
        }
        var bytes = Files.readAllBytes(secret);
        if (bytes.length > 0 && bytes[0] == '-') bytes = Base64.getMimeDecoder().decode(Files.readString(keyFile).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", ""));
        var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes));
        if (key.getModulus().bitLength() < 3072) throw new GeneralSecurityException("发行私钥要求 RSA 3072 位或以上");
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(new java.security.spec.RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        Files.write(signed.resolve("public.der"), publicKey.getEncoded());
        var tools = signed.resolve("version/mod"); Files.createDirectories(tools);
        var b64 = Base64.getEncoder();
        var modulus = key.getModulus().toByteArray(); if (modulus[0] == 0) modulus = Arrays.copyOfRange(modulus, 1, modulus.length);
        String manager = Files.readString(templates.resolve("Manage.ps1")).replace("@MODULUS@", b64.encodeToString(modulus))
                .replace("@EXPONENT@", b64.encodeToString(key.getPublicExponent().toByteArray())).replace("@FLAVOR@", flavor);
        Files.writeString(tools.resolve("Manage.ps1"), "\ufeff" + manager);
        for (String name : List.of("Manage.cmd", "Launch.cmd")) Files.copy(templates.resolve(name), tools.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(templates.resolve("Install.cmd"), signed.resolve("Install.cmd"), StandardCopyOption.REPLACE_EXISTING);
        var files = inventory(version);
        for (String name : List.of("Manage.ps1", "Manage.cmd", "Launch.cmd")) files.put("mod/" + name, sha(tools.resolve(name)));
        var manifest = ("SSOptimizer-Windows-x64-v2\n" + id + "\n" + flavor + "\n" + entries(files)).getBytes(StandardCharsets.UTF_8);
        var sig = Signature.getInstance("SHA256withRSA"); sig.initSign(key); sig.update(manifest);
        Files.write(signed.resolve("version/release.manifest"), manifest); Files.write(signed.resolve("version/release.sig"), sig.sign());
    }
}
