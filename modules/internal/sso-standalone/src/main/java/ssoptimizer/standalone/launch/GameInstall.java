package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
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
        boolean localized = Files.isRegularFile(core.resolve("localization_version.json"))
                || location == DataLocation.GAME && digest(game.getFirst()).equals("2e207732388bfb8290406362647c3578e00a4fdb6c1a4b9ef04a68001f69ed82");
        String profile = localized ? "windows-zh-rc8" : "windows-en-rc8";
        return new GameInstall(root, core, profile, game);
    }

    List<Path> libraries(Path home) throws IOException {
        var result = new java.util.ArrayList<Path>();
        for (String name : LIBRARIES) result.add(required(core.resolve(name)));
        result.add(serializer(System.getenv("SSO_XSTREAM")));
        result.addAll(jars(home.resolve("game-lib")));
        return List.copyOf(result);
    }

    private static final String MIKO = "xstream-1.4.21_miko.jar", UPSTREAM = "xstream-1.4.21.jar", STOCK = "xstream-1.4.10.jar";
    private static final Pattern CLASSPATH = Pattern.compile("(?:^|\\s)(?:-cp|-classpath|--class-path)(?:\\s+|=)(?:\"([^\"]+)\"|'([^']+)'|([^\\s\"']+))");

    Path serializer(String override) throws IOException {
        Path miko = core.resolve(MIKO), upstream = core.resolve(UPSTREAM), stock = core.resolve(STOCK);
        Path bundle = root.resolve("mikohime").resolve(MIKO);
        Path simple = root.resolve("Miko_Simple.txt");
        boolean installed = Files.isRegularFile(simple) || Files.isRegularFile(root.resolve("Miko_Rouge.bat"));
        var candidates = new java.util.ArrayList<>(List.of(miko, upstream, stock));
        if (installed) candidates.add(bundle);
        if (override != null && !override.isBlank()) {
            Path pick = switch (override) {
                case "core-miko" -> miko;
                case "miko" -> {
                    if (!installed) throw new IOException("SSO_XSTREAM=miko 需要安装目录中的 Miko_Simple.txt 或 Miko_Rouge.bat");
                    yield bundle;
                }
                case "upstream" -> upstream;
                case "stock" -> stock;
                default -> throw new IOException("SSO_XSTREAM 应为 core-miko、miko、upstream 或 stock：" + override);
            };
            return selected(pick, "SSO_XSTREAM=" + override);
        }
        for (String name : List.of("launch_nanoforge_ss.sh", "Miko_Simple.txt", "starsector.sh", "vmparams")) {
            Path script = root.resolve(name);
            Path declared = declaredSerializer(script, candidates);
            if (declared != null) return selected(declared, script + " 的 classpath");
            if (name.equals("Miko_Simple.txt") && installed && Files.isRegularFile(bundle)) {
                return selected(bundle, "Mikohime 安装标志与 mikohime 目录");
            }
        }
        for (Path pick : List.of(miko, upstream, stock)) {
            if (Files.isRegularFile(pick)) return selected(pick, "核心目录中的已安装版本");
        }
        throw new IOException("安装内缺少 XStream：" + candidates);
    }

    private Path declaredSerializer(Path script, List<Path> candidates) throws IOException {
        if (!Files.isRegularFile(script)) return null;
        Path file = confined(script);
        try (var in = Files.newInputStream(file)) {
            int limit = 256 * 1024;
            byte[] bytes = in.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("启动配置超过 256 KiB：" + file + "；请设置 SSO_XSTREAM");
            String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("\\\r\n", " ").replace("\\\n", " ");
            var picks = new LinkedHashSet<Path>();
            for (String line : text.lines().toList()) {
                String command = line.stripLeading();
                if (command.startsWith("#") || command.startsWith("echo ") || command.startsWith("printf ")) continue;
                int comment = command.indexOf(" #");
                var matches = CLASSPATH.matcher(comment < 0 ? command : command.substring(0, comment));
                while (matches.find()) {
                    String cp = matches.group(1) != null ? matches.group(1) : matches.group(2) != null ? matches.group(2) : matches.group(3);
                    for (String part : cp.split(cp.contains(";") ? ";" : ":")) {
                        String path = part.replace('\\', '/').replace("//", "/");
                        for (Path candidate : candidates) {
                            String relative = core.relativize(candidate).toString().replace('\\', '/');
                            if (path.equals(relative) || path.equals("./" + relative) || path.equals(candidate.toString().replace('\\', '/'))) picks.add(candidate);
                        }
                    }
                }
            }
            if (picks.size() > 1) throw new IOException(file + " 声明了多个 XStream：" + picks + "；请设置 SSO_XSTREAM");
            return picks.isEmpty() ? null : picks.getFirst();
        }
    }

    private Path selected(Path jar, String reason) throws IOException {
        Path path = confined(required(jar));
        System.getLogger(GameInstall.class.getName()).log(System.Logger.Level.INFO, "使用 XStream：{0}，选择依据：{1}", path, reason);
        return path;
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
