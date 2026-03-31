package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinConfigTest {
    @Test
    void jarContainsMixinConfig() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists());

        try (JarFile jar = new JarFile(jarFile)) {
            var entry = jar.getEntry("mixins.ssoptimizer.json");
            assertNotNull(entry);
            try (InputStream is = jar.getInputStream(entry)) {
                String content = new String(is.readAllBytes());
                assertTrue(content.contains("github.kasuminova.ssoptimizer.mixin"));
                assertTrue(content.contains("render.GEngineRenderMixin"));
                assertTrue(content.contains("accessor.ContrailGroupAccessor"));
            }
        }
    }
}
