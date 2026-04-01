package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinConfigTest {
    @Test
    void jarContainsMixinConfig() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mixins.ssoptimizer.json")) {
            assertNotNull(is, "mixins.ssoptimizer.json 应出现在测试 classpath 上");
            String content = new String(is.readAllBytes());
            assertTrue(content.contains("github.kasuminova.ssoptimizer.mixin"));
            assertTrue(content.contains("render.EngineRenderMixin"));
            assertTrue(content.contains("accessor.ContrailGroupAccessor"));
        }
    }
}
