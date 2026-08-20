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

/**
 * 离线基准的模组插件引导。
 *
 * <p>{@code CampaignGameManager.getXStream} 末尾会对每个启用模组插件执行
 * {@link ModPlugin#configureXStream}（注册模组 alias/属性别名），缺少这些别名会导致
 * 读含模组别名的存档时元素解析失败。</p>
 *
 * <p>{@link ModManager#getEnabledModPlugins()} 的原版实现经
 * {@code StarfarerSettings.getNewPluginInstance} → {@code ScriptStore.getScriptInstance}
 * 取 core 插件，后者依赖游戏启动期填充的 {@code objectRepository}（data/scripts/plugins
 * 自动实例化仓库），离线为空且不可达。因此本类等价重建插件列表（core 插件按
 * settings.json 的 plugins.coreLifecyclePlugin 类名直实例化，模组插件按
 * {@code ModSpecAPI.getModPluginClassName} 经 {@link ScriptStore#loadScriptInstance}
 * 实例化），再注入 {@code ModManager.plugins} 缓存字段，使 {@code getXStream} 内部的
 * {@code getEnabledModPlugins()} 直接命中缓存。</p>
 *
 * <p>插件实例化失败属于环境装配错误，直接抛出，不做静默跳过。</p>
 */
public final class ModPluginBootstrap {
    private static final Logger LOGGER = Logger.getLogger(ModPluginBootstrap.class);

    private ModPluginBootstrap() {
    }

    /**
     * 实例化全部启用模组的 ModPlugin（含 coreLifecyclePlugin）并注入 ModManager 缓存。
     *
     * @return 插件实例列表
     */
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

    /**
     * 将插件列表注入 {@code ModManager.plugins} 缓存。
     *
     * <p>反射理由（AGENTS.md 例外条款）：{@code plugins} 为 private 且无 setter，
     * 唯一的公开填充路径 {@code getEnabledModPlugins()} 依赖游戏启动期的脚本仓库状态
     * （见类注释），离线无其他可达入口。注入的是与原版逻辑等价构造的列表。</p>
     */
    private static void injectPluginCache(final List<ModPlugin> plugins) throws Exception {
        final Field field = ModManager.class.getDeclaredField("plugins");
        field.setAccessible(true);
        field.set(ModManager.getInstance(), plugins);
    }
}
