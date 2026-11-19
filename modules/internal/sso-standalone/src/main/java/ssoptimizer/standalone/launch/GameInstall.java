package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public record GameInstall(Path root, Path core, String profile, List<Path> gameJars) {
    private static final List<String> GAME = List.of("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar");
    private static final List<String> LIBRARIES = List.of("janino.jar", "commons-compiler.jar", "commons-compiler-jdk.jar",
            "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "json.jar", "jinput.jar", "txw2-3.0.2.jar", "jaxb-api-2.4.0-b180830.0359.jar", "webp-imageio-0.1.6.jar");

    public static GameInstall inspect(Path gameRoot) throws IOException {
        return inspect(gameRoot, DataLocation.configured());
    }

    static GameInstall inspect(Path gameRoot, DataLocation location) throws IOException {
        Path root = gameRoot.toAbsolutePath().normalize();
        if (root.getFileName() != null && root.getFileName().toString().equalsIgnoreCase("starsector-core")
                && Files.isRegularFile(root.resolve("data/config/settings.json"))) root = root.getParent();
        Path core = Files.isDirectory(root.resolve("starsector-core")) ? root.resolve("starsector-core") : root;
        if (!Files.isRegularFile(core.resolve("data/config/settings.json"))) {
            throw new IOException("未找到 RC8 游戏资源：" + core + "。请先安装 0.98a-RC8 英文或常规汉化版：https://api.fossic.org/games");
        }
        List<Path> game = GAME.stream().map(core::resolve).toList();
        return new GameInstall(root, core, "rc8", game);
    }

    List<Path> libraries(Path home) throws IOException {
        var result = new java.util.ArrayList<Path>();
        for (String name : LIBRARIES) result.add(required(core.resolve(name)));
        result.add(serializer(System.getenv("SSO_XSTREAM")));
        for (Path jar : jars(home.resolve("game-lib"))) {
            if (!isSerializerName(jar.getFileName().toString())) result.add(jar);
        }
        return List.copyOf(result);
    }

    private static final Pattern XSTREAM_NAME = Pattern.compile("(?i)xstream(?:[-_.].+)?\\.jar");
    private static final Pattern CLASSPATH = Pattern.compile("(?:^|\\s)(?:-cp|-classpath|--class-path)(?:\\s+|=)(?:\"([^\"]+)\"|'([^']+)'|([^\\s\"']+))");
    private static final Set<String> LEGACY_OVERRIDES = Set.of("core-miko", "miko", "upstream", "stock");

    Path serializer(String override) throws IOException {
        if (override != null && !override.isBlank()) {
            return selected(explicitSerializer(override), "SSO_XSTREAM");
        }
        for (String name : List.of("launch_nanoforge_ss.sh", "starsector.sh", "starsector.bat", "starsector.cmd", "vmparams")) {
            Path script = root.resolve(name);
            Path declared = declaredSerializer(script);
            if (declared != null) return selected(declared, script + " 的 classpath");
        }
        List<Path> candidates;
        try (var paths = Files.list(core)) {
            candidates = paths.filter(p -> isSerializerName(p.getFileName().toString()) && Files.isRegularFile(p)).sorted().toList();
        }
        if (candidates.size() == 1) return selected(confined(candidates.getFirst()), "核心目录中的唯一候选库");
        String problem = candidates.isEmpty() ? "核心目录中没有 XStream JAR：" + core : "核心目录中有多个 XStream JAR：" + candidates;
        throw new IOException(problem + "；请通过 SSO_XSTREAM 指定游戏使用的 JAR 路径");
    }

    private static boolean isSerializerName(String file) {
        return XSTREAM_NAME.matcher(file).matches();
    }

    private Path declaredSerializer(Path script) throws IOException {
        if (!Files.isRegularFile(script)) return null;
        Path file = confined(script);
        try (var in = Files.newInputStream(file)) {
            int limit = 256 * 1024;
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("启动配置超过 256 KiB：" + file + "；请设置 SSO_XSTREAM");
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("\\\r\n", " ").replace("\\\n", " ").replace("^\r\n", " ").replace("^\n", " ");
            var picks = new LinkedHashSet<Path>();
            for (String line : text.lines().toList()) {
                String command = line.stripLeading();
                if (command.startsWith("echo ") || command.startsWith("printf ") || command.startsWith("::")
                        || command.regionMatches(true, 0, "rem ", 0, 4)) continue;
                var matches = CLASSPATH.matcher(command.substring(0, commentStart(command)));
                while (matches.find()) {
                    String cp = matches.group(1) != null ? matches.group(1) : matches.group(2) != null ? matches.group(2) : matches.group(3);
                    String separator = cp.contains(";") || (!script.getFileName().toString().endsWith(".sh")
                            && java.io.File.pathSeparatorChar == ';') ? ";" : ":";
                    for (String part : cp.split(separator)) {
                        String path = part.replace('\\', '/');
                        String relative = path.startsWith("%~dp0") ? path.substring(5) : path;
                        String name = relative.substring(relative.lastIndexOf('/') + 1);
                        if (!isSerializerName(name)) continue;
                        if (path.startsWith("%~dp0")) picks.add(confined(required(script.getParent().resolve(relative))));
                        else picks.add(resolveSerializer(path));
                    }
                }
            }
            if (picks.size() > 1) throw new IOException(file + " 声明了多个 XStream：" + picks + "；请设置 SSO_XSTREAM");
            return picks.isEmpty() ? null : picks.getFirst();
        }
    }

    private static int commentStart(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) return i;
        }
        return line.length();
    }

    private Path explicitSerializer(String value) throws IOException {
        Path path = serializerPath(value);
        if (path.isAbsolute()) return required(path).toRealPath();
        if (LEGACY_OVERRIDES.contains(value) && !Files.isRegularFile(core.resolve(path)) && !Files.isRegularFile(root.resolve(path))) {
            throw new IOException("SSO_XSTREAM=" + value + " 使用了旧别名；请改填先前使用的 XStream JAR 路径");
        }
        return resolveSerializer(value);
    }

    private Path resolveSerializer(String value) throws IOException {
        Path path = serializerPath(value);
        if (path.isAbsolute()) return confined(required(path));
        Path relative = core.resolve(path);
        if (Files.isRegularFile(relative)) return confined(relative);
        return confined(required(root.resolve(path)));
    }

    private static Path serializerPath(String value) throws IOException {
        try {
            return Path.of(value);
        } catch (InvalidPathException e) {
            throw new IOException("XStream JAR 路径无效：" + value, e);
        }
    }

    private static Path selected(Path path, String reason) throws IOException {
        verifySerializerAbi(path);
        System.getLogger(GameInstall.class.getName()).log(System.Logger.Level.INFO, "使用 XStream：{0}，选择依据：{1}", path, reason);
        return path;
    }

    private static void verifySerializerAbi(Path path) throws IOException {
        String type = "com/thoughtworks/xstream/XStream";
        try (var archive = new JarFile(path.toFile(), true, JarFile.OPEN_READ, JarFile.runtimeVersion())) {
            var entry = archive.getJarEntry(type + ".class");
            if (entry == null) throw new IOException("缺少 " + type);
            try (var in = archive.getInputStream(entry)) {
                int limit = 4 * 1024 * 1024;
                byte[] bytes = in.readNBytes(limit + 1);
                if (bytes.length > limit) throw new IOException("XStream 类超过 4 MiB");
                var model = ClassFile.of().parse(bytes);
                if (!model.thisClass().asInternalName().equals(type)
                        || (model.flags().flagsMask() & ClassFile.ACC_PUBLIC) == 0) throw new IOException("XStream 类声明无效");
                for (String signature : List.of("fromXML(Ljava/io/InputStream;)Ljava/lang/Object;", "toXML(Ljava/lang/Object;Ljava/io/OutputStream;)V")) {
                    if (model.methods().stream().noneMatch(m -> (m.flags().flagsMask() & (ClassFile.ACC_PUBLIC | ClassFile.ACC_STATIC)) == ClassFile.ACC_PUBLIC
                            && (m.methodName().stringValue() + m.methodType().stringValue()).equals(signature))) {
                        throw new IOException("缺少公开实例方法 " + signature);
                    }
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            throw new IOException("XStream JAR ABI 校验失败：" + path + "：" + e.getMessage(), e);
        }
    }

    private Path confined(Path path) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(root.toRealPath())) throw new IOException("安装文件位于游戏目录外：" + path + " -> " + real);
        return real;
    }

    static List<Path> jars(Path dir) throws IOException {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
        }
    }

    static Path required(Path p) throws IOException {
        if (!Files.isRegularFile(p)) throw new IOException("所需文件不存在：" + p);
        return p;
    }

    static String digest(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) { return digest(in); }
    }

    static String digest(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) != -1;) digest.update(buf, 0, n);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
