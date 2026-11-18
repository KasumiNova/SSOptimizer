package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public record GameInstall(Path root, Path core, String profile, List<Path> gameJars) {
    private static final List<String> GAME = List.of("starfarer_obf.jar", "starfarer.api.jar", "fs.common_obf.jar", "fs.sound_obf.jar");
    static final String UPSTREAM_XSTREAM = "f56586f3de59ae2a49430acbc9f27942b8c5cebec9245c869fae7136733333ec";
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
        String profile = Files.isRegularFile(core.resolve("localization_version.json")) ? "windows-zh-rc8" : "windows-en-rc8";
        return new GameInstall(root, core, profile, game);
    }

    List<Path> libraries(Path home) throws IOException {
        var result = new java.util.ArrayList<Path>();
        for (String name : LIBRARIES) result.add(required(core.resolve(name)));
        List<Path> replacements = jars(home.resolve("game-lib"));
        long verified = 0;
        for (Path path : replacements) {
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).contains("xstream")) {
                if (!UPSTREAM_XSTREAM.equals(digest(path))) throw new IOException("XStream 与已验证的上游 1.4.21 不一致：" + path);
                verified++;
            }
        }
        if (verified != 1) throw new IOException("game-lib 必须仅包含一个上游 XStream 1.4.21；当前为 " + verified);
        result.addAll(replacements);
        return List.copyOf(result);
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
