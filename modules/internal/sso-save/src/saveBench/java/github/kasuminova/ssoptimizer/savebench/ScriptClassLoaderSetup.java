package github.kasuminova.ssoptimizer.savebench;

import com.fs.starfarer.api.ModSpecAPI;
import com.fs.starfarer.loading.scripts.ScriptStore;
import org.apache.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ScriptClassLoaderSetup {
    private static final Logger LOGGER = Logger.getLogger(ScriptClassLoaderSetup.class);

    private ScriptClassLoaderSetup() {
    }

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

    /** 须在 SpecStore 加载后、模组 onApplicationLoad 前调用。 */
    public static void finish() throws Exception {
        ScriptStore.waitForLoading();
        LOGGER.info("[SaveBench] script loading finished");
    }
}
