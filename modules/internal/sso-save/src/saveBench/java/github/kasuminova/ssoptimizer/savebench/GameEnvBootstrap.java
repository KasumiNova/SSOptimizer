package github.kasuminova.ssoptimizer.savebench;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.launcher.ModManager;
import com.fs.starfarer.settings.StarfarerSettings;
import com.fs.util.ResourceLoader;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class GameEnvBootstrap {
    private static final Logger LOGGER = Logger.getLogger(GameEnvBootstrap.class);

    private GameEnvBootstrap() {
    }

    public static List<? extends ModSpecAPI> setup(final Path gameDir) throws Exception {
        if (!Files.isRegularFile(gameDir.resolve("data").resolve("config").resolve("settings.json"))) {
            throw new IllegalArgumentException("gameDir 不含 data/config/settings.json（非 Linux 布局游戏根目录）: " + gameDir);
        }
        setPathProperty("com.fs.starfarer.settings.paths.saves", gameDir.resolve("saves"));
        setPathProperty("com.fs.starfarer.settings.paths.mods", gameDir.resolve("mods"));
        setPathProperty("com.fs.starfarer.settings.paths.logs", gameDir.resolve("logs"));
        setPathProperty("com.fs.starfarer.settings.paths.screenshots", gameDir.resolve("screenshots"));

        // ModManager 读取 enabled_mods.json 依赖已注册的游戏根资源。
        final ResourceLoader resourceLoader = ResourceLoader.getInstance();
        resourceLoader.addDirectoryResource(gameDir.toString());
        resourceLoader.addAbsoluteAndCwdResource();

        final List<? extends ModSpecAPI> enabledMods = ModManager.getInstance().getEnabledMods();
        LOGGER.info("[SaveBench] enabled mods: " + enabledMods.size());

        for (final ModSpecAPI spec : enabledMods) {
            final Path dir = gameDir.resolve("mods").resolve(spec.getDirName());
            if (Files.isDirectory(dir)) {
                resourceLoader.addDirectoryResource(dir.toString());
            }
        }

        StarfarerSettings.loadSettings();
        if (StarfarerSettings.isDevMode()) {
            throw new IllegalStateException("离线基准要求 devMode=false（devMode 会注册 InterfacePassthroughConverter 改变序列化行为）");
        }
        Global.setSettings(StarfarerSettings.getSettingsAPI());
        LOGGER.info("[SaveBench] settings loaded, devMode=false");
        return enabledMods;
    }

    private static void setPathProperty(final String key, final Path value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value.toString());
        }
    }
}
