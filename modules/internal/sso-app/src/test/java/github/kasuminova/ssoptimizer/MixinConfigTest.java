package github.kasuminova.ssoptimizer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinConfigTest {
    private static final List<String> MIXIN_LISTS = List.of("mixins", "client", "server");

    private static JsonObject loadConfig() throws IOException {
        try (InputStream input = MixinConfigTest.class.getClassLoader().getResourceAsStream("mixins.ssoptimizer.json")) {
            assertNotNull(input, "mixins.ssoptimizer.json 应出现在测试 classpath 上");
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static List<String> registeredMixins(final JsonObject config) {
        final List<String> names = new ArrayList<>();
        for (final String key : MIXIN_LISTS) {
            if (!config.has(key)) {
                continue;
            }
            for (final JsonElement mixin : config.getAsJsonArray(key)) {
                names.add(mixin.getAsString());
            }
        }
        return names;
    }

    private static boolean isOnClasspath(final String className) {
        return MixinConfigTest.class.getClassLoader().getResource(className.replace('.', '/') + ".class") != null;
    }

    @Test
    void everyRegisteredMixinResolvesToClass() throws IOException {
        final JsonObject config = loadConfig();
        final String basePackage = config.get("package").getAsString();
        final List<String> mixins = registeredMixins(config);
        assertFalse(mixins.isEmpty(), "mixin 配置必须登记至少一个 mixin");
        for (final String mixin : mixins) {
            assertTrue(isOnClasspath(basePackage + "." + mixin), "登记的 mixin 找不到对应类: " + mixin);
        }
    }

    @Test
    void configPluginResolvesToClass() throws IOException {
        final String plugin = loadConfig().get("plugin").getAsString();
        assertTrue(isOnClasspath(plugin), "mixin 配置声明的 plugin 找不到对应类: " + plugin);
    }

    @Test
    void noMixinIsRegisteredTwice() throws IOException {
        final Set<String> seen = new HashSet<>();
        for (final String mixin : registeredMixins(loadConfig())) {
            assertTrue(seen.add(mixin), "mixin 被重复登记: " + mixin);
        }
    }
}
