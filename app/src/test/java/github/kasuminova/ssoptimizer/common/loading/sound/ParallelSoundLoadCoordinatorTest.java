package github.kasuminova.ssoptimizer.common.loading.sound;

import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
    void objectFamilyUsesItsMatchingStreamOverloadBeforeOriginalPathFallback() throws Exception {
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

        assertEquals("object-stream:sounds/ui/cached.ogg", result);
        assertArrayEquals(expected, manager.loadedBytes);
        assertEquals(GameMemberNames.SoundManager.LOAD_OBJECT_FAMILY_FROM_STREAM, manager.lastStreamMethodUsed);
        assertFalse(manager.originalPathFallbackUsed);
    }

    @Test
    void o00000FamilyUsesItsMatchingStreamOverloadBeforeOriginalPathFallback() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        final Path soundFile = gameRoot.resolve("sounds/ui/cached.ogg");
        Files.createDirectories(soundFile.getParent());
        final byte[] expected = new byte[]{6, 7, 8, 9};
        Files.write(soundFile, expected);

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        final Object result = ParallelSoundLoadCoordinator.loadO00000Family(manager, "sounds/ui/cached.ogg");

        assertEquals("o00000-stream:sounds/ui/cached.ogg", result);
        assertArrayEquals(expected, manager.loadedBytes);
        assertEquals(GameMemberNames.SoundManager.LOAD_O00000_FAMILY_FROM_STREAM, manager.lastStreamMethodUsed);
        assertFalse(manager.originalPathFallbackUsed);
    }

    @Test
    void oAccentFamilyUsesItsMatchingStreamOverloadBeforeOriginalPathFallback() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        final Path soundFile = gameRoot.resolve("sounds/ui/cached.ogg");
        Files.createDirectories(soundFile.getParent());
        final byte[] expected = new byte[]{10, 11, 12};
        Files.write(soundFile, expected);

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        final Object result = ParallelSoundLoadCoordinator.loadOAccentFamily(manager, "sounds/ui/cached.ogg");

        assertEquals("object-stream:sounds/ui/cached.ogg", result);
        assertArrayEquals(expected, manager.loadedBytes);
        assertEquals(GameMemberNames.SoundManager.LOAD_O_ACCENT_FAMILY_FROM_STREAM, manager.lastStreamMethodUsed);
        assertFalse(manager.originalPathFallbackUsed);
    }

    @Test
    void fallsBackToOriginalPathWhenPreloadedStreamLoadThrows() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        final Path soundFile = gameRoot.resolve("sounds/ui/cached.ogg");
        Files.createDirectories(soundFile.getParent());
        Files.write(soundFile, new byte[]{1, 2, 3});

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        manager.throwOnStreamLoad = true;

        final Object result = ParallelSoundLoadCoordinator.loadO00000Family(manager, "sounds/ui/cached.ogg");

        assertEquals("fallback-o00000:sounds/ui/cached.ogg", result);
        assertTrue(manager.originalPathFallbackUsed);
    }

    @SuppressWarnings("unused")
    private static final class FakeSoundManager {
        private byte[]  loadedBytes;
        private boolean originalPathFallbackUsed;
        private String  lastStreamMethodUsed;
        private boolean throwOnStreamLoad;

        private Object loadObjectFamilyFromStream(final String resourcePath,
                                                  final InputStream inputStream) throws Exception {
            return streamLoaded(GameMemberNames.SoundManager.LOAD_OBJECT_FAMILY_FROM_STREAM,
                    "object-stream:", resourcePath, inputStream);
        }

        private Object loadO00000FamilyFromStream(final String resourcePath,
                                                  final InputStream inputStream) throws Exception {
            return streamLoaded(GameMemberNames.SoundManager.LOAD_O00000_FAMILY_FROM_STREAM,
                    "o00000-stream:", resourcePath, inputStream);
        }

        private Object loadOAccentFamilyFromStream(final String resourcePath,
                                                   final InputStream inputStream) throws Exception {
            return streamLoaded(GameMemberNames.SoundManager.LOAD_O_ACCENT_FAMILY_FROM_STREAM,
                    "object-stream:", resourcePath, inputStream);
        }

        private Object streamLoaded(final String methodName,
                                    final String prefix,
                                    final String resourcePath,
                                    final InputStream inputStream) throws Exception {
            if (throwOnStreamLoad) {
                throw new IOException("simulated preload failure");
            }

            this.lastStreamMethodUsed = methodName;
            this.loadedBytes = inputStream.readAllBytes();
            return prefix + resourcePath;
        }

        private Object ssoptimizer$loadPathObjectFamily(final String resourcePath) {
            this.originalPathFallbackUsed = true;
            return "fallback-object:" + resourcePath;
        }

        private Object ssoptimizer$loadPathO00000Family(final String resourcePath) {
            this.originalPathFallbackUsed = true;
            return "fallback-o00000:" + resourcePath;
        }

        private Object ssoptimizer$loadPathOAccentFamily(final String resourcePath) {
            this.originalPathFallbackUsed = true;
            return "fallback-oaccent:" + resourcePath;
        }
    }
}