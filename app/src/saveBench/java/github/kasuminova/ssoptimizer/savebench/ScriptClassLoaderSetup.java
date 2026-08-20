package github.kasuminova.ssoptimizer.savebench;

import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.loading.scripts.ScriptStore;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 离线基准的脚本类加载器装配，复刻游戏启动序列（{@code ResourceLoaderState}）。
 *
 * <p>真实存档包含大量 {@code data.scripts.*} 元素（Janino 运行期编译的脚本类，
 * 实测一份 58MB 战役档含 193 种），离线反序列化必须能按名加载这些类；
 * 同时多个游戏类的 {@code readResolve}（如 {@code LogisticsModule}）会经
 * {@code ScriptStore.getFirstScript} 访问启动期填充的脚本实例仓库
 * （{@code objectRepository}），该仓库只能由脚本加载线程填充。</p>
 *
 * <p>游戏启动顺序：{@code createScriptClassLoader()} → 注册模组插件类 →
 * {@code startLoadingThread()}（内部创建 sourceClassLoader 并后台编译/实例化脚本）→
 * {@code scanScriptDirectories()} → 资源与 SpecStore 加载（并发）→
 * {@code waitForLoading()} → 模组插件 {@code onApplicationLoad}。
 * 本类按同一顺序拆为 {@link #begin} / {@link #finish} 两段，
 * 由调用方把 SpecStore 加载夹在中间。</p>
 *
 * <p>脚本源码经游戏原版 {@code ScriptSourceFinder} 从 ResourceLoader 资源根读取
 * （{@code GameEnvBootstrap} 已注册游戏根与全部启用模组根），模组 jar 按
 * mod_info.json 的 {@code jars} 数组显式声明装配（与游戏一致），不扫描 jars/ 目录。</p>
 */
public final class ScriptClassLoaderSetup {
    private static final Logger LOGGER = Logger.getLogger(ScriptClassLoaderSetup.class);

    private ScriptClassLoaderSetup() {
    }

    /**
     * 启动脚本加载：创建 ScriptClassLoader、装配模组 jar 清单、启动后台编译线程并扫描脚本目录。
     *
     * @param gameDir     游戏根目录
     * @param enabledMods 启用模组清单
     */
    public static void begin(final Path gameDir, final List<? extends ModSpecAPI> enabledMods) throws Exception {
        ScriptStore.createScriptClassLoader();

        int jarCount = 0;
        for (final ModSpecAPI spec : enabledMods) {
            final Path modDir = gameDir.resolve("mods").resolve(spec.getDirName());
            for (final String jarRel : spec.getJars()) {
                final Path jar = modDir.resolve(jarRel);
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalStateException("模组声明的 jar 不存在: " + jar + " (mod=" + spec.getId() + ")");
                }
                ScriptStore.getJarFiles().add(jar.toAbsolutePath().toString());
                jarCount++;
            }
        }
        LOGGER.info("[SaveBench] mod jars registered: " + jarCount);

        ScriptStore.startLoadingThread();
        ScriptStore.scanScriptDirectories();
        LOGGER.info("[SaveBench] script loading thread started");
    }

    /**
     * 等待脚本加载线程完成（编译并实例化全部脚本类，填充实例仓库）。
     * 必须在 SpecStore 加载完成之后、模组插件 {@code onApplicationLoad} 之前调用。
     */
    public static void finish() throws Exception {
        ScriptStore.waitForLoading();
        LOGGER.info("[SaveBench] script loading finished");
    }
}
