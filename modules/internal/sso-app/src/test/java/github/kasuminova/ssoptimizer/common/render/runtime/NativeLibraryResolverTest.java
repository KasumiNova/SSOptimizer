package github.kasuminova.ssoptimizer.common.render.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NativeLibraryResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesMainModuleFromModFolder() throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve(expectedPlatformFolder());
        Files.createDirectories(nativeDir);
        Path soFile = nativeDir.resolve(System.mapLibraryName("ssoptimizer_render"));
        Files.writeString(soFile, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve());
        assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve(NativeLibraryResolver.MAIN_MODULE));
    }

    @Test
    void resolvesPerModuleLibraryFromModFolder() throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve(expectedPlatformFolder());
        Files.createDirectories(nativeDir);
        Path soFile = nativeDir.resolve(System.mapLibraryName("ssoptimizer_loading"));
        Files.writeString(soFile, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.clearProperty("ssoptimizer.native.path.loading");
        try {
            assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve("loading"));
        } finally {
            System.clearProperty("ssoptimizer.native.path.loading");
        }
    }

    @Test
    void moduleSpecificPropertyOverridesModFolder() throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve(expectedPlatformFolder());
        Files.createDirectories(nativeDir);
        Path modFolderLib = nativeDir.resolve(System.mapLibraryName("ssoptimizer_ime"));
        Files.writeString(modFolderLib, "stub");

        Path overrideLib = tempDir.resolve(System.mapLibraryName("ssoptimizer_ime"));
        Files.writeString(overrideLib, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty("ssoptimizer.native.path.ime", overrideLib.toString());
        try {
            assertEquals(overrideLib.toAbsolutePath(), NativeLibraryResolver.resolve("ime"));
        } finally {
            System.clearProperty("ssoptimizer.native.path.ime");
        }
    }

    @Test
    void generalOverrideStillAppliesToMainModule() throws Exception {
        Path overrideLib = tempDir.resolve(System.mapLibraryName("ssoptimizer_render"));
        Files.writeString(overrideLib, "stub");

        System.setProperty("ssoptimizer.native.path", overrideLib.toString());
        try {
            assertEquals(overrideLib.toAbsolutePath(), NativeLibraryResolver.resolve());
        } finally {
            System.clearProperty("ssoptimizer.native.path");
        }
    }

    private static String expectedPlatformFolder() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("mac")) {
            return "mac";
        }
        return "linux";
    }
}
