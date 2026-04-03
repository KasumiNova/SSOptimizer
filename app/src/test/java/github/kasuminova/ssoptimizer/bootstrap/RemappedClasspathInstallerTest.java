package github.kasuminova.ssoptimizer.bootstrap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemappedClasspathInstaller} 的辅助行为测试。
 */
class RemappedClasspathInstallerTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsRuntimeRemappedJarWhenConfigured() throws Exception {
        final Path remappedJar = tempDir.resolve("runtime-input.jar");
        Files.writeString(remappedJar, "test-remapped-jar");
        final Path exportDir = tempDir.resolve("exported");

        System.setProperty(RemappedClasspathInstaller.EXPORT_DIR_PROPERTY, exportDir.toString());
        try {
            RemappedClasspathInstaller.exportRuntimeRemappedJarIfConfigured(remappedJar);
        } finally {
            System.clearProperty(RemappedClasspathInstaller.EXPORT_DIR_PROPERTY);
        }

        final Path exportedJar = exportDir.resolve("ssoptimizer-runtime-remapped.jar");
        assertTrue(Files.isRegularFile(exportedJar));
        assertEquals("test-remapped-jar", Files.readString(exportedJar));
    }
}