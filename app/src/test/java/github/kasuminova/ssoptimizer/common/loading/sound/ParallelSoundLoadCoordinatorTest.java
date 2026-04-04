package github.kasuminova.ssoptimizer.common.loading.sound;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelSoundLoadCoordinatorTest {
    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        System.clearProperty("com.fs.starfarer.settings.paths.mods");
        System.clearProperty(ParallelSoundLoadCoordinator.DISABLE_PROPERTY);
        System.clearProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY);
        System.clearProperty(ParallelSoundLoadCoordinator.MAX_MEMORY_BYTES_PROPERTY);
        ParallelSoundLoadCoordinator.clearForTests();
    }

    @Test
    void discoversGameAndModSoundResources() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        Files.createDirectories(gameRoot.resolve("sounds/ui"));
        Files.createDirectories(modsDir.resolve("testmod/sounds/weapons"));
        Files.writeString(gameRoot.resolve("sounds/ui/click.ogg"), "click", StandardCharsets.UTF_8);
        Files.writeString(modsDir.resolve("testmod/sounds/weapons/fire.wav"), "fire", StandardCharsets.UTF_8);

        final List<String> resources = ParallelSoundLoadCoordinator.discoverSoundResourcesForTests(gameRoot, modsDir);

        assertTrue(resources.contains("sounds/ui/click.ogg"));
        assertTrue(resources.contains("sounds/weapons/fire.wav"));
    }

    @Test
    void objectFamilyUsesPreloadedBytesBeforeOriginalPathFallback() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        final Path soundFile = gameRoot.resolve("sounds/ui/cached.ogg");
        Files.createDirectories(soundFile.getParent());
        final byte[] expected = new byte[]{1, 2, 3, 4, 5};
        Files.write(soundFile, expected);

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        final Object result = ParallelSoundLoadCoordinator.loadObjectFamily(manager, "sounds/ui/cached.ogg");

        assertEquals("cached:sounds/ui/cached.ogg", result);
        assertArrayEquals(expected, manager.loadedBytes);
        assertFalse(manager.originalPathFallbackUsed);
    }

    @SuppressWarnings("unused")
    private static final class FakeSoundManager {
        private byte[]  loadedBytes;
        private boolean originalPathFallbackUsed;

        private Object Object(final String resourcePath,
                              final InputStream inputStream) throws Exception {
            this.loadedBytes = inputStream.readAllBytes();
            return "cached:" + resourcePath;
        }

        private Object ssoptimizer$loadPathObjectFamily(final String resourcePath) {
            this.originalPathFallbackUsed = true;
            return "fallback:" + resourcePath;
        }
    }
}