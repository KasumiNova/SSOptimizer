package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public record GameInstall(Path root, Path core, String profile, List<Path> gameJars) {
    private static final List<String> GAME = List.of("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar");
    private static final List<String> LIBRARIES = List.of("janino.jar", "commons-compiler.jar", "commons-compiler-jdk.jar",
            "jogg-0.0.7.jar", "jorbis-0.0.15.jar", "json.jar", "jinput.jar", "txw2-3.0.2.jar", "jaxb-api-2.4.0-b180830.0359.jar", "webp-imageio-0.1.6.jar");

    public static GameInstall inspect(Path gameRoot) throws IOException {
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
        for (String name : LIBRARIES) result.add(core.resolve(name));
        result.add(serializer(System.getenv("SSO_XSTREAM")));
        for (Path jar : jars(home.resolve("game-lib"))) {
            if (!isSerializerName(jar.getFileName().toString())) result.add(jar);
        }
        return List.copyOf(result);
    }

    private static final Pattern XSTREAM_NAME = Pattern.compile("(?i)xstream(?:[-_.].+)?\\.jar");
    private static final Pattern CLASSPATH = Pattern.compile("(?:^|\\s)(?:-cp|-classpath|--class-path)(?:\\s+|=)(?:\"([^\"]+)\"|'([^']+)'|([^\\s\"']+))");

    Path serializer(String override) throws IOException {
        if (override != null && !override.isBlank()) {
            return selected(resolveSerializer(override), "SSO_XSTREAM");
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
        if (candidates.size() == 1) return selected(candidates.getFirst().toRealPath(), "核心目录中的唯一候选库");
        String problem = candidates.isEmpty() ? "核心目录中没有 XStream JAR：" + core : "核心目录中有多个 XStream JAR：" + candidates;
        throw new IOException(problem + "；请通过 SSO_XSTREAM 指定游戏使用的 JAR 路径");
    }

    private static boolean isSerializerName(String file) {
        return XSTREAM_NAME.matcher(file).matches();
    }

    private Path declaredSerializer(Path script) throws IOException {
        if (!Files.isRegularFile(script)) return null;
        String text = new String(Files.readAllBytes(script), java.nio.charset.StandardCharsets.UTF_8)
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
                    if (path.startsWith("%~dp0")) picks.add(script.getParent().resolve(relative).toRealPath());
                    else picks.add(resolveSerializer(path));
                }
            }
        }
        if (picks.size() > 1) throw new IOException(script + " 声明了多个 XStream：" + picks + "；请设置 SSO_XSTREAM");
        return picks.isEmpty() ? null : picks.getFirst();
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

    private Path resolveSerializer(String value) throws IOException {
        Path path = Path.of(value);
        if (path.isAbsolute()) return path.toRealPath();
        Path relative = core.resolve(path);
        return (Files.isRegularFile(relative) ? relative : root.resolve(path)).toRealPath();
    }

    private static Path selected(Path path, String reason) throws IOException {
        System.getLogger(GameInstall.class.getName()).log(System.Logger.Level.INFO, "使用 XStream：{0}，选择依据：{1}", path, reason);
        return path;
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
}
