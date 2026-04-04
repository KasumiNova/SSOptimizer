package github.kasuminova.ssoptimizer.common.loading.script;

import org.codehaus.janino.JavaSourceClassLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JaninoScriptCompilerCoordinatorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY);
        System.clearProperty(JaninoScriptCompilerCoordinator.DISABLE_CACHE_PROPERTY);
        System.clearProperty(JaninoScriptCompilerCoordinator.DISABLE_PREWARM_PROPERTY);
        System.clearProperty(JaninoScriptCompilerCoordinator.PARALLELISM_PROPERTY);
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
    }

    @Test
    void generateBytecodesReusesDiskCacheAfterFirstCompilation() throws Exception {
        final Path sourceRoot = tempDir.resolve("scripts");
        final Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(sourceRoot.resolve("pkg"));
        Files.writeString(sourceRoot.resolve("pkg/TestScript.java"),
                "package pkg; public class TestScript { public static final int VALUE = 42; }",
                StandardCharsets.UTF_8);

        System.setProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY, cacheDir.toString());

        final ExposedJavaSourceClassLoader loader = new ExposedJavaSourceClassLoader(sourceRoot.toFile());

        final Map<String, byte[]> first = JaninoScriptCompilerCoordinator.generateBytecodes(loader, "pkg.TestScript");
        assertNotNull(first);
        assertNotNull(first.get("pkg.TestScript"));
        assertEquals(1, loader.originalGenerateCalls());
        assertTrue(Files.isRegularFile(cacheDir.resolve("pkg/TestScript.class")));

        final Map<String, byte[]> second = JaninoScriptCompilerCoordinator.generateBytecodes(loader, "pkg.TestScript");
        assertNotNull(second);
        assertNotNull(second.get("pkg.TestScript"));
        assertEquals(1, loader.originalGenerateCalls(), "Second lookup should be satisfied from the disk cache");
    }

    @Test
    void warmupPrecompilesDiscoveredScriptsIntoCacheDirectory() throws Exception {
        final Path sourceRoot = tempDir.resolve("scripts");
        final Path cacheDir = tempDir.resolve("cache");
        Files.createDirectories(sourceRoot.resolve("pkg"));
        Files.writeString(sourceRoot.resolve("pkg/FirstScript.java"),
                "package pkg; public class FirstScript { public static final int VALUE = 1; }",
                StandardCharsets.UTF_8);
        Files.writeString(sourceRoot.resolve("pkg/SecondScript.java"),
                "package pkg; public class SecondScript { public static final int VALUE = 2; }",
                StandardCharsets.UTF_8);

        System.setProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY, cacheDir.toString());
        System.setProperty(JaninoScriptCompilerCoordinator.PARALLELISM_PROPERTY, "2");

        final ExposedJavaSourceClassLoader loader = new ExposedJavaSourceClassLoader(sourceRoot.toFile());

        JaninoScriptCompilerCoordinator.warmup(loader, "pkg.FirstScript");

        assertTrue(JaninoScriptCompilerCoordinator.awaitWarmupForTests(loader, Duration.ofSeconds(10)));
        assertTrue(Files.isRegularFile(cacheDir.resolve("pkg/FirstScript.class")));
        assertTrue(Files.isRegularFile(cacheDir.resolve("pkg/SecondScript.class")));
        assertFalse(loader.originalGenerateCalls() < 0);
    }

    private static final class ExposedJavaSourceClassLoader extends JavaSourceClassLoader {
        private int originalGenerateCalls;

        private ExposedJavaSourceClassLoader(final File sourceRoot) {
            super(JaninoScriptCompilerCoordinatorTest.class.getClassLoader(), new File[]{sourceRoot}, null);
        }

        @SuppressWarnings("unused")
        private Map<String, byte[]> ssoptimizer$generateBytecodesOriginal(final String className) throws ClassNotFoundException {
            originalGenerateCalls++;
            return super.generateBytecodes(className);
        }

        private int originalGenerateCalls() {
            return originalGenerateCalls;
        }
    }
}