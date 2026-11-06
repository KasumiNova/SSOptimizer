package github.kasuminova.ssoptimizer.gradle;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsOverlayDistributionContractTest {
    private static Path overlayRoot() {
        final String dir = System.getProperty("ssoptimizer.windowsOverlay.dir");
        assertNotNull(dir, "构建脚本必须通过 ssoptimizer.windowsOverlay.dir 传入 stageWindowsOverlay 产物目录");
        final Path root = Path.of(dir);
        assertTrue(Files.isDirectory(root), "stageWindowsOverlay 产物目录不存在: " + root);
        return root;
    }

    @Test
    void modDirectoryIsStagedUnderRootMods() throws IOException {
        final Path mod = overlayRoot().resolve("mods/ssoptimizer");
        assertTrue(Files.isRegularFile(mod.resolve("mod_info.json")), "缺少 mod_info.json");
        assertTrue(Files.isRegularFile(mod.resolve("nanoforge.mod.toml")), "缺少 nanoforge.mod.toml");
        assertTrue(Files.isRegularFile(mod.resolve("jars/SSOptimizer.jar")), "缺少模组 jar");

        final Path fonts = mod.resolve("fonts");
        assertTrue(Files.isDirectory(fonts), "缺少 fonts 目录");
        try (Stream<Path> files = Files.list(fonts)) {
            assertTrue(files.anyMatch(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ttf")),
                    "fonts 目录必须包含 TTF 字体");
        }
    }

    @Test
    void coreModEntryJarIsIdenticalToModJar() throws IOException {
        final Path root = overlayRoot();
        final Path entry = root.resolve("mods/coremods/SSOptimizer.jar");
        assertTrue(Files.isRegularFile(entry), "缺少 NanoForge coremod 入口 jar");
        assertEquals(-1L, Files.mismatch(entry, root.resolve("mods/ssoptimizer/jars/SSOptimizer.jar")),
                "coremod 入口 jar 必须与模组目录内的 jar 内容一致");
    }

    @Test
    void log4jConfigIsPlacedInStarsectorCore() throws IOException {
        final String rootDir = System.getProperty("project.rootDir");
        assertNotNull(rootDir, "缺少 project.rootDir");
        final Path staged = overlayRoot().resolve("starsector-core/log4j.properties");
        assertTrue(Files.isRegularFile(staged), "缺少 starsector-core/log4j.properties");
        assertEquals(-1L, Files.mismatch(staged, Path.of(rootDir, "log4j.properties")),
                "安装包内的 log4j.properties 必须与仓库根目录一致");
    }

    @Test
    void overlayContainsNoLinuxNativesAndNoMisplacedModDirectory() {
        final Path root = overlayRoot();
        assertFalse(Files.exists(root.resolve("mods/ssoptimizer/native/linux")), "Windows overlay 不应包含 Linux 原生库");
        assertFalse(Files.exists(root.resolve("starsector-core/mods")), "Windows overlay 不应把模组放进 starsector-core/mods");
    }
}
