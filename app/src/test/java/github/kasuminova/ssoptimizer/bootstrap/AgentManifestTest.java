package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

class AgentManifestTest {
    @Test
    void jarContainsPremainClass() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists(), "SSOptimizer.jar must exist before checking manifest");

        try (JarFile jar = new JarFile(jarFile)) {
            var manifest = jar.getManifest();
            assertNotNull(manifest);
            assertEquals("github.kasuminova.ssoptimizer.bootstrap.SSOptimizerAgent",
                    manifest.getMainAttributes().getValue("Premain-Class"));
            assertEquals("true", manifest.getMainAttributes().getValue("Can-Retransform-Classes"));
            assertEquals("true", manifest.getMainAttributes().getValue("Can-Redefine-Classes"));
        }
    }

    @Test
    void jarContainsBundledBytecodeLibraries() throws Exception {
        File jarFile = new File(System.getProperty("project.rootDir"), "app/build/libs/SSOptimizer.jar");
        assertTrue(jarFile.exists());

        try (JarFile jar = new JarFile(jarFile)) {
            assertNotNull(jar.getEntry("org/objectweb/asm/ClassReader.class"));
            assertNotNull(jar.getEntry("org/spongepowered/asm/mixin/Mixin.class"));
        }
    }
}
