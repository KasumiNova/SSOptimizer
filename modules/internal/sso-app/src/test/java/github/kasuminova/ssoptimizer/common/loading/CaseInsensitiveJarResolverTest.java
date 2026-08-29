package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CaseInsensitiveJarResolver} 的真实文件系统逻辑验证。
 */
class CaseInsensitiveJarResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void exactExistingPathPassesThroughUnchanged() throws Exception {
        final Path jar = Files.write(tempDir.resolve("mod.jar"), new byte[]{0});
        // 声明路径带冗余 ./ 段时也与原版一致原样透传
        final String declared = tempDir + "/./mod.jar";
        assertEquals(declared, CaseInsensitiveJarResolver.resolve(declared));
        assertEquals(jar.toString(), CaseInsensitiveJarResolver.resolve(jar.toString()));
    }

    @Test
    void caseMismatchedPathResolvesToActualFile() throws Exception {
        final Path jarsDir = Files.createDirectories(tempDir.resolve("Mods/SomeMod/jars"));
        final Path actual = Files.write(jarsDir.resolve("secretsOfTheFrontier.jar"), new byte[]{0});

        final String declared = tempDir + "/mods/somemod/jars/secretsofthefrontier.jar";
        assertEquals(actual.toString(), CaseInsensitiveJarResolver.resolve(declared));
    }

    @Test
    void trulyMissingJarPassesThroughUnchanged() {
        final String declared = tempDir + "/mods/ghost/jars/nope.jar";
        assertEquals(declared, CaseInsensitiveJarResolver.resolve(declared));
    }

    @Test
    void blankAndNullPathsPassThrough() {
        assertEquals("", CaseInsensitiveJarResolver.resolve(""));
        assertEquals(null, CaseInsensitiveJarResolver.resolve(null));
    }

    @Test
    void resolvingViewResolvesOnAdd() throws Exception {
        final Path jarsDir = Files.createDirectories(tempDir.resolve("mod/jars"));
        final Path actual = Files.write(jarsDir.resolve("RealName.jar"), new byte[]{0});

        final List<String> jarFiles = new ArrayList<>();
        final List<String> view = CaseInsensitiveJarResolver.resolvingView(jarFiles);

        view.add(tempDir + "/mod/jars/realname.jar");
        view.add(tempDir + "/mod/jars/missing.jar");

        assertEquals(List.of(actual.toString(), tempDir + "/mod/jars/missing.jar"), jarFiles);
        assertEquals(2, view.size());
        assertEquals(actual.toString(), view.get(0));
    }

    @Test
    void directoryListingCacheReusedAcrossResolutions() throws Exception {
        final Path jarsDir = Files.createDirectories(tempDir.resolve("m/jars"));
        final Path a = Files.write(jarsDir.resolve("Alpha.jar"), new byte[]{0});
        final Path b = Files.write(jarsDir.resolve("Beta.jar"), new byte[]{0});

        assertEquals(a.toString(), CaseInsensitiveJarResolver.resolve(tempDir + "/m/jars/alpha.jar"));
        // 第二次解析命中目录清单缓存，结果一致
        assertEquals(b.toString(), CaseInsensitiveJarResolver.resolve(tempDir + "/m/jars/beta.jar"));
        assertTrue(a.toString().endsWith("Alpha.jar"));
    }
}
