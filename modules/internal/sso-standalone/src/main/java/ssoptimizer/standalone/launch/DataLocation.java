package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Path;

enum DataLocation {
    INSTANCE, GAME;

    static DataLocation configured() throws IOException {
        if (!"1".equals(System.getenv("SSO_USE_GAME_DATA"))) return INSTANCE;
        if (!"Linux".equalsIgnoreCase(System.getProperty("os.name"))) {
            throw new IOException("SSO_USE_GAME_DATA=1 仅支持 Linux 私有挂载命名空间中的游戏安装。");
        }
        return GAME;
    }

    Path root(GameInstall game, Path instance) {
        return this == GAME ? game.root() : instance;
    }
}
