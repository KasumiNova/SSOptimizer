package github.kasuminova.ssoptimizer.common.loading.script;

import org.codehaus.janino.JavaSourceClassLoader;
import org.codehaus.janino.util.resource.Resource;
import org.codehaus.janino.util.resource.ResourceFinder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    @Test
    void contentHashCacheWorksWithNonDirectoryFinder() {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY, cacheDir.toString());

        final InMemoryScriptFinder finder = new InMemoryScriptFinder();
        finder.sources.put("pkg/ModScript.java", "package pkg; class V1 {}".getBytes(StandardCharsets.UTF_8));
        final JavaSourceClassLoader loader = new JavaSourceClassLoader(
                JaninoScriptCompilerCoordinatorTest.class.getClassLoader(), finder, "UTF-8");

        final byte[] compiledBytes = {10, 20, 30, 40};

        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript"));

        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(loader, "pkg.ModScript",
                Map.of("pkg.ModScript", compiledBytes));

        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        final Map<String, byte[]> hit = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript");
        assertNotNull(hit, "非目录型 finder 的磁盘缓存必须可命中");
        assertArrayEquals(compiledBytes, hit.get("pkg.ModScript"));

        finder.sources.put("pkg/ModScript.java", "package pkg; class V2 {}".getBytes(StandardCharsets.UTF_8));
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript"),
                "源码内容变化后磁盘缓存必须失效");
    }

    @Test
    void batchWriteStampsEachEntryWithItsOwnSourceHash() {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY, cacheDir.toString());

        final InMemoryScriptFinder finder = new InMemoryScriptFinder();
        finder.sources.put("pkg/ScriptA.java", "package pkg; class A { int a = 1; }".getBytes(StandardCharsets.UTF_8));
        finder.sources.put("pkg/ScriptB.java", "package pkg; class B { int b = 2; }".getBytes(StandardCharsets.UTF_8));
        final JavaSourceClassLoader loader = new JavaSourceClassLoader(
                JaninoScriptCompilerCoordinatorTest.class.getClassLoader(), finder, "UTF-8");

        final byte[] bytesA = {1, 1, 1};
        final byte[] bytesB = {2, 2, 2};

        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(loader, "pkg.ScriptA",
                Map.of("pkg.ScriptA", bytesA, "pkg.ScriptB", bytesB));

        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        final Map<String, byte[]> hitA = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptA");
        final Map<String, byte[]> hitB = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptB");
        assertNotNull(hitA, "批量写入后 ScriptA 必须命中");
        assertNotNull(hitB, "批量写入后 ScriptB 必须命中");
        assertArrayEquals(bytesA, hitA.get("pkg.ScriptA"));
        assertArrayEquals(bytesB, hitB.get("pkg.ScriptB"));

        finder.sources.put("pkg/ScriptB.java", "package pkg; class B { int b = 3; }".getBytes(StandardCharsets.UTF_8));
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        assertNotNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptA"),
                "ScriptA 的缓存不应受 ScriptB 源码变化影响");
        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptB"),
                "ScriptB 源码变化后其缓存必须失效");
    }

    private static final class InMemoryScriptFinder extends ResourceFinder {
        private final Map<String, byte[]> sources = new HashMap<>();

        @Override
        public Resource findResource(final String resourceName) {
            final byte[] data = sources.get(resourceName);
            if (data == null) {
                return null;
            }
            return new Resource() {
                @Override
                public InputStream open() {
                    return new ByteArrayInputStream(data);
                }

                @Override
                public long lastModified() {
                    return 0L;
                }

                @Override
                public String getFileName() {
                    return resourceName;
                }
            };
        }
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