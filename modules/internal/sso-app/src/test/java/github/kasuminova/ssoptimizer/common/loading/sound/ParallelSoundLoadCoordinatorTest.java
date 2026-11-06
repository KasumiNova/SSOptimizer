package github.kasuminova.ssoptimizer.common.loading.sound;

import github.kasuminova.ssoptimizer.mixin.loading.SoundManagerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sound.Audio;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void objectFamilyLoadsPreloadedBytesThroughItsOwnStreamInvoker() throws Exception {
        final byte[] expected = new byte[]{1, 2, 3, 4, 5};
        final FakeSoundManager manager = prepareSoundFile(expected);

        final Object result = ParallelSoundLoadCoordinator.loadObjectFamily(manager, "sounds/ui/cached.ogg");

        assertLoadedThrough(StreamFamily.OBJECT, "sounds/ui/cached.ogg", result, manager);
        assertArrayEquals(expected, manager.loadedBytes);
    }

    @Test
    void o00000FamilyLoadsPreloadedBytesThroughItsOwnStreamInvoker() throws Exception {
        final byte[] expected = new byte[]{6, 7, 8, 9};
        final FakeSoundManager manager = prepareSoundFile(expected);

        final Object result = ParallelSoundLoadCoordinator.loadO00000Family(manager, "sounds/ui/cached.ogg");

        assertLoadedThrough(StreamFamily.O00000, "sounds/ui/cached.ogg", result, manager);
        assertArrayEquals(expected, manager.loadedBytes);
    }

    @Test
    void oAccentFamilyLoadsPreloadedBytesThroughItsOwnStreamInvoker() throws Exception {
        final byte[] expected = new byte[]{10, 11, 12};
        final FakeSoundManager manager = prepareSoundFile(expected);

        final Object result = ParallelSoundLoadCoordinator.loadOAccentFamily(manager, "sounds/ui/cached.ogg");

        assertLoadedThrough(StreamFamily.O_ACCENT, "sounds/ui/cached.ogg", result, manager);
        assertArrayEquals(expected, manager.loadedBytes);
    }

    @Test
    void returnsNullSoOriginalLoaderRunsWhenPreloadedStreamLoadThrows() throws Exception {
        final FakeSoundManager manager = prepareSoundFile(new byte[]{1, 2, 3});
        manager.throwOnStreamLoad = true;

        final Object result = ParallelSoundLoadCoordinator.loadO00000Family(manager, "sounds/ui/cached.ogg");

        assertNull(result, "流加载失败应返回 null，由 SoundManagerMixin 放行原版路径加载");
    }

    @Test
    void inflightEntriesAreRemovedAfterLoadAndPrewarmComplete() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        Files.createDirectories(gameRoot.resolve("sounds"));
        Files.writeString(gameRoot.resolve("sounds/a.ogg"), "aaa", StandardCharsets.UTF_8);
        Files.writeString(gameRoot.resolve("sounds/b.ogg"), "bbb", StandardCharsets.UTF_8);
        Files.writeString(gameRoot.resolve("sounds/c.ogg"), "ccc", StandardCharsets.UTF_8);

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        ParallelSoundLoadCoordinator.loadObjectFamily(manager, "sounds/a.ogg");

        awaitInflightDrain();
        assertEquals(0, ParallelSoundLoadCoordinator.inflightLoadCountForTests(),
                "加载与预载完成后 INFLIGHT_LOADS 应清空");
    }

    @Test
    void inflightEntryRemovedWhenPrewarmLoadFails() throws Exception {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        Files.createDirectories(gameRoot.resolve("sounds"));
        Files.writeString(gameRoot.resolve("sounds/good.ogg"), "good", StandardCharsets.UTF_8);
        final Path broken = gameRoot.resolve("sounds/broken.ogg");
        Files.writeString(broken, "broken", StandardCharsets.UTF_8);
        assumeTrue(broken.toFile().setReadable(false),
                "平台不支持文件权限位（如 Windows），跳过预载失败路径断言");

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");

        final FakeSoundManager manager = new FakeSoundManager();
        ParallelSoundLoadCoordinator.loadObjectFamily(manager, "sounds/good.ogg");

        awaitInflightDrain();
        assertEquals(0, ParallelSoundLoadCoordinator.inflightLoadCountForTests(),
                "预载失败条目也必须移除（future 不得滞留）");
    }

    private FakeSoundManager prepareSoundFile(final byte[] content) throws IOException {
        final Path gameRoot = tempDir.resolve("game");
        final Path modsDir = gameRoot.resolve("mods");
        final Path soundFile = gameRoot.resolve("sounds/ui/cached.ogg");
        Files.createDirectories(soundFile.getParent());
        Files.write(soundFile, content);

        System.setProperty("com.fs.starfarer.settings.paths.mods", modsDir.toString());
        System.setProperty(ParallelSoundLoadCoordinator.PARALLELISM_PROPERTY, "2");
        return new FakeSoundManager();
    }

    private static void assertLoadedThrough(final StreamFamily expectedFamily,
                                            final String expectedPath,
                                            final Object result,
                                            final FakeSoundManager manager) {
        final FakeAudio audio = assertInstanceOf(FakeAudio.class, result);
        assertEquals(expectedFamily, audio.family);
        assertEquals(expectedPath, audio.resourcePath);
        assertEquals(expectedFamily, manager.lastFamily);
    }

    private static void awaitInflightDrain() throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (ParallelSoundLoadCoordinator.inflightLoadCountForTests() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
    }

    private enum StreamFamily {
        OBJECT,
        O00000,
        O_ACCENT
    }

    private static final class FakeSoundManager implements SoundManagerAccessor {
        private byte[]       loadedBytes;
        private StreamFamily lastFamily;
        private boolean      throwOnStreamLoad;

        @Override
        public Audio ssoptimizer$loadObjectFamilyFromStream(final String resourcePath,
                                                            final InputStream inputStream) throws IOException {
            return streamLoaded(StreamFamily.OBJECT, resourcePath, inputStream);
        }

        @Override
        public Audio ssoptimizer$loadO00000FamilyFromStream(final String resourcePath,
                                                            final InputStream inputStream) throws IOException {
            return streamLoaded(StreamFamily.O00000, resourcePath, inputStream);
        }

        @Override
        public Audio ssoptimizer$loadOAccentFamilyFromStream(final String resourcePath,
                                                             final InputStream inputStream) throws IOException {
            return streamLoaded(StreamFamily.O_ACCENT, resourcePath, inputStream);
        }

        private Audio streamLoaded(final StreamFamily family,
                                   final String resourcePath,
                                   final InputStream inputStream) throws IOException {
            if (throwOnStreamLoad) {
                throw new IOException("simulated preload failure");
            }

            this.lastFamily = family;
            this.loadedBytes = inputStream.readAllBytes();
            return new FakeAudio(family, resourcePath);
        }
    }

    private static final class FakeAudio implements Audio {
        private final StreamFamily family;
        private final String       resourcePath;

        private FakeAudio(final StreamFamily family, final String resourcePath) {
            this.family = family;
            this.resourcePath = resourcePath;
        }

        @Override
        public void stop() {
        }

        @Override
        public int getBufferId() {
            return 0;
        }

        @Override
        public boolean isPlaying() {
            return false;
        }

        @Override
        public int play(final float pitch, final float gain, final boolean loop) {
            return 0;
        }

        @Override
        public void playManaged(final float pitch, final float gain, final boolean loop) {
        }

        @Override
        public int play(final float pitch, final float gain, final boolean loop,
                        final float x, final float y, final float z) {
            return 0;
        }

        @Override
        public int play(final float pitch, final float gain, final boolean loop,
                        final float x, final float y, final float z,
                        final float vx, final float vy, final float vz) {
            return 0;
        }

        @Override
        public boolean setTimeOffset(final float offset) {
            return false;
        }

        @Override
        public float getTimeOffset() {
            return 0f;
        }

        @Override
        public int getSourceId() {
            return 0;
        }

        @Override
        public void setVelocity(final float x, final float y, final float z) {
        }

        @Override
        public void setPosition(final float x, final float y, final float z) {
        }

        @Override
        public void setVolume(final float volume) {
        }

        @Override
        public void setPitch(final float pitch) {
        }

        @Override
        public float getLength() {
            return 0f;
        }

        @Override
        public boolean isMono() {
            return false;
        }
    }
}
