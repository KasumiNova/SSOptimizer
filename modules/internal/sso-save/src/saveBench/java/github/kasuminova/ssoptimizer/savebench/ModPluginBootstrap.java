package github.kasuminova.ssoptimizer.savebench;

import com.fs.starfarer.api.ModPlugin;
import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.launcher.ModManager;
import com.fs.starfarer.loading.scripts.ScriptStore;
import com.fs.starfarer.settings.StarfarerSettings;
import org.apache.log4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public final class ModPluginBootstrap {
    private static final Logger LOGGER = Logger.getLogger(ModPluginBootstrap.class);

    private ModPluginBootstrap() {
    }

    public static List<ModPlugin> setup() throws Exception {
        final List<ModPlugin> plugins = new ArrayList<>();

        final String corePluginClass = StarfarerSettings.getPluginClassName("coreLifecyclePlugin");
        plugins.add((ModPlugin) ScriptStore.loadScriptInstance(corePluginClass));
        LOGGER.info("[SaveBench] core plugin instantiated: " + corePluginClass);

        for (final ModSpecAPI spec : ModManager.getInstance().getEnabledMods()) {
            final String pluginClass = spec.getModPluginClassName();
            if (pluginClass == null || pluginClass.isBlank()) {
                continue;
            }
            plugins.add((ModPlugin) ScriptStore.loadScriptInstance(pluginClass));
            LOGGER.info("[SaveBench] mod plugin instantiated: " + pluginClass);
        }

        injectPluginCache(plugins);
        LOGGER.info("[SaveBench] mod plugins ready: " + plugins.size());
        return plugins;
    }

    // ModManager.plugins 无公开写入入口，离线环境无法经原版脚本仓库填充。
    private static void injectPluginCache(final List<ModPlugin> plugins) throws Exception {
        final Field field = ModManager.class.getDeclaredField("plugins");
        field.setAccessible(true);
        field.set(ModManager.getInstance(), plugins);
    }
}
