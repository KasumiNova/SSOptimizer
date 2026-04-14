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
    void resolvesNativeLibraryFromModFolder() throws Exception {
        Path modsDir = tempDir.resolve("mods");
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve(expectedPlatformFolder());
        Files.createDirectories(nativeDir);
        Path soFile = nativeDir.resolve(System.mapLibraryName("ssoptimizer"));
        Files.writeString(soFile, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve());
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
