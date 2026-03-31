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
        Path nativeDir = modsDir.resolve("ssoptimizer").resolve("native").resolve("linux");
        Files.createDirectories(nativeDir);
        Path soFile = nativeDir.resolve(System.mapLibraryName("ssoptimizer"));
        Files.writeString(soFile, "stub");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        assertEquals(soFile.toAbsolutePath(), NativeLibraryResolver.resolve());
    }
}
