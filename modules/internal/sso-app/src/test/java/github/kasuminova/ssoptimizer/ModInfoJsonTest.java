package github.kasuminova.ssoptimizer;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发布元数据契约测试。
 * <p>
 * mod_info.json 由 SDG 插件从 :app 的 starsector {} DSL 生成（唯一事实源，不手写），
 * 本测试直接校验生成产物（test 任务已 dependsOn modProduction）。
 */
class ModInfoJsonTest {

    private static Path generatedModInfo() {
        return Path.of(System.getProperty("project.rootDir"),
                "modules", "internal", "sso-app", "build", "mod_production", "mod_info.json");
    }

    @Test
    void modInfoJsonGenerated() {
        assertTrue(Files.exists(generatedModInfo()),
                "mod_info.json must be generated into modules/internal/sso-app/build/mod_production by SDG");
    }

    @Test
    void modInfoJsonContainsRequiredFields() throws Exception {
        JSONObject info = new JSONObject(Files.readString(generatedModInfo()));
        assertAll(
                () -> assertEquals("ssoptimizer", info.getString("id"), "must have id field"),
                () -> assertEquals("SSOptimizer", info.getString("name"), "must have name field"),
                () -> assertFalse(info.getString("version").isBlank(), "must have version field"),
                () -> assertEquals("0.98a-RC8", info.getString("gameVersion"), "must have gameVersion field"),
                () -> assertEquals("github.kasuminova.ssoptimizer.SSOptimizerModPlugin",
                        info.getString("modPlugin"), "must have modPlugin field"),
                () -> assertEquals("jars/SSOptimizer.jar", info.getJSONArray("jars").getString(0),
                        "must point runtime jar to canonical SSOptimizer.jar")
        );
    }
}
