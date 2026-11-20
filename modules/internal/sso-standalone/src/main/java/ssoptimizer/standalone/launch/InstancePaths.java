package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

final class InstancePaths {
    private InstancePaths() {}

    static Path validate(GameInstall game, Path home, Path requested, DataLocation location) throws IOException {
        Path root = physical(requested.toAbsolutePath().normalize());
        if (game.root().startsWith(root) || home.startsWith(root) || root.startsWith(home)
                || !game.root().equals(game.core()) && root.startsWith(game.core())) {
            throw new IOException("实例不得覆盖游戏、核心资源或版本目录：" + root);
        }
        for (Path base : List.of(game.root(), game.core())) for (String key : List.of("mods", "saves", "data", "native", "jre", "screenshots", "logs")) {
            if (root.startsWith(physical(base.resolve(key)))) throw new IOException("禁止将实例指向原游戏数据：" + root);
        }
        for (String name : List.of("cache", "cache/lwjgl-v1", "cache/named", "cache/obf", "saves", "session.lock", "jvm.properties", "jvm.args", "compiler-directives.json", ".standalone-layout")) {
            confined(root, root.resolve(name));
        }
        if (location == DataLocation.INSTANCE) {
            for (String name : List.of("logs", "mods", "screenshots")) confined(root, root.resolve(name));
        }
        return root;
    }

    static void confined(Path root, Path path) throws IOException {
        if (!path.startsWith(root) || !physical(path).equals(path) || Files.isSymbolicLink(path)) {
            throw new IOException("内部路径重定向到其他位置：" + path + " (" + root + ")");
        }
    }

    private static Path physical(Path p) throws IOException {
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return p.toRealPath();
        return physical(p.getParent()).resolve(p.getFileName());
    }
}
