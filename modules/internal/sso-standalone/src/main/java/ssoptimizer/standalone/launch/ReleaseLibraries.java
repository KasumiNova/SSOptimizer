package ssoptimizer.standalone.launch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.jar.JarFile;

final class ReleaseLibraries {
    private ReleaseLibraries() {}

    static void verify(Path home) throws IOException {
        var resource = ReleaseLibraries.class.getResourceAsStream("/ssoptimizer/standalone/libraries.lock");
        if (resource == null) throw new IOException("独立入口缺少发行依赖清单");
        var expected = new HashSet<Path>();
        try (var text = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = text.readLine()) != null;) {
                String[] fields = line.split(" ", 4);
                String[] coordinate = fields[2].split(":");
                Path path = home.resolve(fields[0]).resolve(coordinate[1] + "-" + coordinate[2] + ".jar");
                if (!GameInstall.digest(GameInstall.required(path)).equals(fields[1])) throw new IOException("发行依赖摘要不匹配，请重新安装该版本：" + path);
                expected.add(path);
            }
        }
        boolean host = false, remap = false;
        for (String domain : List.of("lib", "game-lib")) for (Path path : GameInstall.jars(home.resolve(domain))) {
            if (expected.contains(path)) continue;
            String name = path.getFileName().toString();
            if (domain.equals("lib")) try (var jar = new JarFile(path.toFile())) {
                if (!host && name.startsWith("sso-host-standalone-") && jar.getEntry("META-INF/rfb-plugin/ssoptimizer-system.properties") != null) { host = true; continue; }
                if (!remap && name.startsWith("sso-remap-") && jar.getEntry("github/kasuminova/ssoptimizer/standalone/remap/ArchivePreparation.class") != null) { remap = true; continue; }
            }
            throw new IOException("版本目录含清单外的归档：" + path);
        }
        if (!host || !remap) throw new IOException("版本目录缺少正确的 RFB 插件或映射工具：" + home.resolve("lib"));
    }
}
