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

    /**
     * 游戏实际场景回归：{@code ScriptSourceFinder} 不是目录型 finder，
     * 基于目录 mtime 的旧校验对它永远失效（缓存只写不读，每次启动全量重编译）。
     * 现统一走内容哈希校验：写入后清空内存缓存模拟新 JVM，
     * 必须能从磁盘命中；源码内容变化后必须失效。
     */
    @Test
    void contentHashCacheWorksWithNonDirectoryFinder() {
        final Path cacheDir = tempDir.resolve("cache");
        System.setProperty(JaninoScriptCompilerCoordinator.CACHE_DIR_PROPERTY, cacheDir.toString());

        final InMemoryScriptFinder finder = new InMemoryScriptFinder();
        finder.sources.put("pkg/ModScript.java", "package pkg; class V1 {}".getBytes(StandardCharsets.UTF_8));
        final JavaSourceClassLoader loader = new JavaSourceClassLoader(
                JaninoScriptCompilerCoordinatorTest.class.getClassLoader(), finder, "UTF-8");

        final byte[] compiledBytes = {10, 20, 30, 40};

        // 写入前读不命中
        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript"));

        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(loader, "pkg.ModScript",
                Map.of("pkg.ModScript", compiledBytes));

        // 模拟新 JVM：清空内存缓存，必须走磁盘 + 哈希校验命中
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        final Map<String, byte[]> hit = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript");
        assertNotNull(hit, "非目录型 finder 的磁盘缓存必须可命中");
        assertArrayEquals(compiledBytes, hit.get("pkg.ModScript"));

        // 源码内容变化后缓存必须失效
        finder.sources.put("pkg/ModScript.java", "package pkg; class V2 {}".getBytes(StandardCharsets.UTF_8));
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ModScript"),
                "源码内容变化后磁盘缓存必须失效");
    }

    /**
     * 游戏定制版 Janino 的 {@code generateBytecodes} 会一次性返回所有已排队编译单元的
     * 批量结果（含与请求类无关的其他脚本类）。回归：批量写入时每个条目必须以其自身
     * 源码哈希为凭据——用请求类哈希统一盖章会污染全部缓存文件，导致后续启动永远 miss。
     */
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

        // 模拟批量返回：请求 ScriptA，但产物里混入了 ScriptB
        JaninoScriptCompilerCoordinator.cacheGeneratedBytecodes(loader, "pkg.ScriptA",
                Map.of("pkg.ScriptA", bytesA, "pkg.ScriptB", bytesB));

        // 模拟新 JVM：两者都必须以各自源码哈希命中磁盘缓存
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        final Map<String, byte[]> hitA = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptA");
        final Map<String, byte[]> hitB = JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptB");
        assertNotNull(hitA, "批量写入后 ScriptA 必须命中");
        assertNotNull(hitB, "批量写入后 ScriptB 必须命中");
        assertArrayEquals(bytesA, hitA.get("pkg.ScriptA"));
        assertArrayEquals(bytesB, hitB.get("pkg.ScriptB"));

        // 只改 ScriptB 的源码：仅 ScriptB 失效，ScriptA 不受影响
        finder.sources.put("pkg/ScriptB.java", "package pkg; class B { int b = 3; }".getBytes(StandardCharsets.UTF_8));
        JaninoScriptCompilerCoordinator.clearWarmupStateForTests();
        assertNotNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptA"),
                "ScriptA 的缓存不应受 ScriptB 源码变化影响");
        assertNull(JaninoScriptCompilerCoordinator.tryLoadCachedBytecodes(loader, "pkg.ScriptB"),
                "ScriptB 源码变化后其缓存必须失效");
    }

    /**
     * 模拟游戏 {@code ScriptSourceFinder} 的内存型 finder：非目录、非 Multi，
     * 只按 Janino 资源名约定（{@code pkg/Class.java}）提供源码字节。
     */
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