package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * coremod jar 产物契约测试：验证打出的 SSOptimizer.jar 是合法的 NanoForge coremod
 * （含 coremod.toml / mixin config / shade 依赖，且不再是 javaagent 胖 jar）。
 */
class CoreModJarTest {

    private static File coreModJar() {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists(), "SSOptimizer.jar must exist before checking coremod contract");
        return jarFile;
    }

    @Test
    void jarContainsCoreModMetaWithPluginAndTransformers() throws Exception {
        try (JarFile jar = new JarFile(coreModJar())) {
            var entry = jar.getEntry("coremod.toml");
            assertNotNull(entry, "coremod.jar 必须含 coremod.toml");

            String toml;
            try (InputStream input = jar.getInputStream(entry)) {
                toml = new String(input.readAllBytes());
            }
            assertTrue(toml.contains("id = \"ssoptimizer\""), "coremod.toml 必须声明 id");
            assertTrue(toml.contains("pluginClass = \"github.kasuminova.ssoptimizer.bootstrap.SSOptimizerCorePlugin\""),
                    "coremod.toml 必须声明 coremod 入口插件");
            assertTrue(toml.contains("github.kasuminova.ssoptimizer.bootstrap.HybridWeaverTransformer"),
                    "coremod.toml 必须声明 ASM transformer");
            assertTrue(toml.contains("mixins.ssoptimizer.json"), "coremod.toml 必须声明 mixin config");
            assertFalse(toml.contains("${projectVersion}"), "coremod.toml 版本占位符必须已被替换");
        }
    }

    @Test
    void jarContainsMixinConfigAndNoPremainManifest() throws Exception {
        try (JarFile jar = new JarFile(coreModJar())) {
            assertNotNull(jar.getEntry("mixins.ssoptimizer.json"), "mixin config json 必须作为 jar 资源存在");

            var manifest = jar.getManifest();
            assertNull(manifest.getMainAttributes().getValue("Premain-Class"),
                    "coremod jar 不应再携带 Premain-Class manifest");
        }
    }

    @Test
    void jarShadesRuntimeDependenciesButNotNanoForgeProvidedLibraries() throws Exception {
        try (JarFile jar = new JarFile(coreModJar())) {
            // shade 进 jar 的运行时依赖
            assertNotNull(jar.getEntry("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap.class"), "fastutil 必须 shade 进 jar");
            assertNotNull(jar.getEntry("org/jctools/queues/MpscArrayQueue.class"), "jctools 必须 shade 进 jar");
            assertNotNull(jar.getEntry("com/github/luben/zstd/Zstd.class"), "zstd-jni 必须 shade 进 jar");
            assertNotNull(jar.getEntry("org/apache/log4j/Logger.class"), "log4j-1.2-api 桥接层必须 shade 进 jar");

            // 运行时由 NanoForge 提供的库不得打进 jar
            assertNull(jar.getEntry("org/objectweb/asm/ClassReader.class"), "ASM 由 NanoForge 运行时提供，不应 shade");
            assertNull(jar.getEntry("org/spongepowered/asm/mixin/Mixin.class"), "sponge-mixin 由 NanoForge 运行时提供，不应 shade");
            assertNull(jar.getEntry("net/minecraft/launchwrapper/IClassTransformer.class"), "LaunchWrapper 由 RFB 提供，不应 shade");
        }
    }
}
