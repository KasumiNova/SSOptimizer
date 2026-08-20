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

/**
 * 离线基准的游戏环境引导：路径属性 + 资源根 + settings.json。
 *
 * <p>游戏存档序列化配置（{@code CampaignGameManager.getXStream}）依赖三组静态状态：
 * {@code com.fs.starfarer.settings.paths.*} 系统属性、{@link ResourceLoader} 资源根
 * （settings.json 经 {@code LoadingUtils.readJSONMerged} 跨资源根合并读取）、
 * 以及 {@link StarfarerSettings#loadSettings()} 的显式加载。本类按游戏启动期的等价
 * 顺序重建这些状态，使后续 {@code getXStream} 与线上行为一致。</p>
 *
 * <p>资源根注册 starsector-core 与全部启用模组目录，与游戏运行期的合并语义对齐
 * （模组可覆盖 settings.json 键值）。</p>
 */
public final class GameEnvBootstrap {
    private static final Logger LOGGER = Logger.getLogger(GameEnvBootstrap.class);

    private GameEnvBootstrap() {
    }

    /**
     * 引导离线环境。
     *
     * <p>以 Linux 发行版布局为准：core 资源直接位于游戏根目录（data/、graphics/ 等），
     * 无 Windows 布局的 starsector-core 子目录。</p>
     *
     * @param gameDir 游戏根目录（含 saves/mods/data）
     * @return 启用模组清单（供脚本类加载器装配与插件实例化使用）
     */
    public static List<? extends ModSpecAPI> setup(final Path gameDir) throws Exception {
        if (!Files.isRegularFile(gameDir.resolve("data").resolve("config").resolve("settings.json"))) {
            throw new IllegalArgumentException("gameDir 不含 data/config/settings.json（非 Linux 布局游戏根目录）: " + gameDir);
        }
        setPathProperty("com.fs.starfarer.settings.paths.saves", gameDir.resolve("saves"));
        setPathProperty("com.fs.starfarer.settings.paths.mods", gameDir.resolve("mods"));
        setPathProperty("com.fs.starfarer.settings.paths.logs", gameDir.resolve("logs"));
        setPathProperty("com.fs.starfarer.settings.paths.screenshots", gameDir.resolve("screenshots"));

        // 游戏根资源必须先于 ModManager 注册：loadEnabledModList 经 LoadingUtils.loadJSON
        // 以绝对路径读 enabled_mods.json，依赖资源根与绝对路径回退。
        final ResourceLoader resourceLoader = ResourceLoader.getInstance();
        resourceLoader.addDirectoryResource(gameDir.toString());
        resourceLoader.addAbsoluteAndCwdResource();

        // ModManager 构造仅扫描 mod_info.json + 读 enabled_mods.json，headless 安全。
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
        // 模组插件/脚本 clinit 常经 Global.getSettings() 取配置（与游戏启动期
        // StarfarerLauncher:254 同源的设置入口）
        Global.setSettings(StarfarerSettings.getSettingsAPI());
        LOGGER.info("[SaveBench] settings loaded, devMode=false");
        return enabledMods;
    }

    private static void setPathProperty(final String key, final Path value) {
        // 允许调用方以 -D 显式覆盖；默认指向 gameDir 内对应目录
        if (System.getProperty(key) == null) {
            System.setProperty(key, value.toString());
        }
    }
}
