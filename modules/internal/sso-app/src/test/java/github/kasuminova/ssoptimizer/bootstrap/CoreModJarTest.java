package github.kasuminova.ssoptimizer.bootstrap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreModJarTest {
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final List<String> MIXIN_LISTS = List.of("mixins", "client", "server");

    private static JarFile jar;

    @BeforeAll
    static void openJar() throws IOException {
        final String path = System.getProperty("ssoptimizer.coremod.jar");
        assertNotNull(path, "构建脚本必须通过 ssoptimizer.coremod.jar 传入 jar 任务产物路径");
        final File file = new File(path);
        assertTrue(file.isFile(), "jar 任务产物不存在: " + file);
        jar = new JarFile(file);
    }

    @AfterAll
    static void closeJar() throws IOException {
        if (jar != null) {
            jar.close();
        }
    }

    private static String readText(final String entryName) throws IOException {
        final var entry = jar.getEntry(entryName);
        assertNotNull(entry, "jar 缺少条目: " + entryName);
        try (var input = jar.getInputStream(entry)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> tomlStrings(final String toml, final String key) {
        final Matcher line = Pattern.compile("^" + key + "\\s*=\\s*(.*)$", Pattern.MULTILINE).matcher(toml);
        assertTrue(line.find(), "coremod.toml 缺少键: " + key);
        final Matcher values = QUOTED.matcher(line.group(1));
        final List<String> result = new ArrayList<>();
        while (values.find()) {
            result.add(values.group(1));
        }
        return result;
    }

    private static void assertClassPackaged(final String className) {
        assertNotNull(jar.getEntry(className.replace('.', '/') + ".class"), "jar 缺少类: " + className);
    }

    @Test
    void descriptorReferencesPackagedClassesAndConfigs() throws IOException {
        final String toml = readText("coremod.toml");
        assertFalse(toml.contains("${"), "coremod.toml 存在未替换的占位符");

        final List<String> plugin = tomlStrings(toml, "pluginClass");
        assertEquals(1, plugin.size(), "pluginClass 必须恰有一个值");
        assertClassPackaged(plugin.get(0));

        final List<String> transformers = tomlStrings(toml, "transformers");
        assertFalse(transformers.isEmpty(), "coremod.toml 必须声明 ASM transformer");
        transformers.forEach(CoreModJarTest::assertClassPackaged);

        final List<String> configs = tomlStrings(toml, "configs");
        assertFalse(configs.isEmpty(), "coremod.toml 必须声明 mixin 配置");
        configs.forEach(config -> assertNotNull(jar.getEntry(config), "jar 缺少 mixin 配置: " + config));
    }

    @Test
    void hybridWeaverRunsBeforeRenderThreadRedirect() throws IOException {
        final List<String> transformers = tomlStrings(readText("coremod.toml"), "transformers");
        final int hybrid = transformers.indexOf(HybridWeaverTransformer.class.getName());
        final int redirect = transformers.indexOf(RenderThreadRedirectTransformer.class.getName());
        assertTrue(hybrid >= 0 && redirect >= 0, "两个 transformer 都必须被声明");
        assertTrue(hybrid < redirect, "HybridWeaverTransformer 必须先于 RenderThreadRedirectTransformer");
    }

    @Test
    void everyRegisteredMixinIsPackaged() throws IOException {
        final JsonObject config = JsonParser.parseString(readText("mixins.ssoptimizer.json")).getAsJsonObject();
        final String basePackage = config.get("package").getAsString();
        assertClassPackaged(config.get("plugin").getAsString());
        for (final String key : MIXIN_LISTS) {
            if (!config.has(key)) {
                continue;
            }
            for (final JsonElement mixin : config.getAsJsonArray(key)) {
                assertClassPackaged(basePackage + "." + mixin.getAsString());
            }
        }
    }

    @Test
    void manifestCarriesNoJavaAgentEntry() throws IOException {
        assertNotNull(jar.getManifest(), "jar 必须含 manifest");
        assertNull(jar.getManifest().getMainAttributes().getValue("Premain-Class"),
                "项目禁止 javaagent，jar 不得携带 Premain-Class");
    }

    @Test
    void shadesRuntimeDependenciesButNotNanoForgeProvidedLibraries() {
        assertNotNull(jar.getEntry("it/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap.class"), "fastutil 必须 shade 进 jar");
        assertNotNull(jar.getEntry("org/jctools/queues/MpscArrayQueue.class"), "jctools 必须 shade 进 jar");
        assertNotNull(jar.getEntry("com/github/luben/zstd/Zstd.class"), "zstd-jni 必须 shade 进 jar");
        assertNotNull(jar.getEntry("org/apache/log4j/Logger.class"), "log4j-1.2-api 桥接层必须 shade 进 jar");
        assertTrue(jar.stream().noneMatch(entry -> entry.getName().startsWith("org/apache/logging/log4j/")
                        && entry.getName().endsWith(".class")),
                "log4j2 API 由运行时提供，不应 shade 进 jar");

        assertNull(jar.getEntry("org/objectweb/asm/ClassReader.class"), "ASM 由 NanoForge 运行时提供，不应 shade");
        assertNull(jar.getEntry("org/spongepowered/asm/mixin/Mixin.class"), "sponge-mixin 由 NanoForge 运行时提供，不应 shade");
        assertNull(jar.getEntry("net/minecraft/launchwrapper/IClassTransformer.class"), "LaunchWrapper 由 RFB 提供，不应 shade");
    }
}
