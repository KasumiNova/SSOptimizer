package github.kasuminova.ssoptimizer;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JarOutputTest {
    @Test
    void jarBuildDirectoryExists() {
        Path libsDir = Path.of(System.getProperty("project.rootDir"), "app", "build", "libs");
        assertTrue(Files.isDirectory(libsDir), "app/build/libs should exist after jar build");
    }
}