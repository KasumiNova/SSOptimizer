package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParallelImagePreloadWorkerTest {
    private final TestFontResourceOverride fontOverride = new TestFontResourceOverride();

    @TempDir
    Path gameDir;

    @BeforeEach
    void setUp() {
        ParallelImagePreloadWorker.installFontResourceOverrideForTests(fontOverride);
    }

    @AfterEach
    void tearDown() {
        fontOverride.reset();
        TexturePreparationRegistry.clear();
        ParallelImagePreloadQueueTracker.clearPending();
        ParallelImagePreloadWorker.resetAccessForTests();
        ParallelImagePreloadWorker.resetFontResourceOverrideForTests();
    }

    @Test
    void fontOverridePathStillCompletesPreparationFuture() throws Exception {
        final String path = "graphics/fonts/insignia12_0.png";
        final Path png = gameDir.resolve(path);
        Files.createDirectories(png.getParent());
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());
        fontOverride.override(path);

        final List<String> imageQueue = new ArrayList<>();
        final Map<String, BufferedImage> imageResults = new ConcurrentHashMap<>();
        final List<String> byteQueue = new ArrayList<>();
        final Map<String, byte[]> byteResults = new ConcurrentHashMap<>();
        final BufferedImage imageSentinel = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        final byte[] byteSentinel = new byte[0];
        ParallelImagePreloadWorker.installAccess(imageQueue, imageResults, byteQueue, byteResults,
                imageSentinel, byteSentinel,
                ignored -> ImageIO.read(png.toFile()),
                ignored -> Files.readAllBytes(png));

        TexturePreparationRegistry.track(path);
        ParallelImagePreloadQueueTracker.enqueueImage(imageQueue, path);

        new ParallelImagePreloadWorker().run();

        assertEquals(path, fontOverride.queriedPath, "worker 必须查询已启用的字体覆盖服务");
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<TexturePreparationRegistry.Prepared> awaiting =
                    executor.submit(() -> TexturePreparationRegistry.await(path));
            final TexturePreparationRegistry.Prepared prepared = awaiting.get(5, TimeUnit.SECONDS);
            assertNull(prepared, "字体覆盖路径未走预备管线，应返回 null 让主线程回退原版路径");
        } finally {
            executor.shutdownNow();
        }

        assertNotNull(ParallelImagePreloadQueueTracker.awaitImage(imageResults, path, imageSentinel),
                "字体覆盖路径应把解码结果交还给等待方");
    }

    private static final class TestFontResourceOverride implements FontResourceOverride {
        private boolean enabled;
        private String overriddenPath;
        private String queriedPath;

        private void override(final String path) {
            enabled = true;
            overriddenPath = path;
            queriedPath = null;
        }

        private void reset() {
            enabled = false;
            overriddenPath = null;
            queriedPath = null;
        }

        @Override
        public String normalize(final String resourcePath) {
            return resourcePath;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isOverriddenPath(final String normalizedPath) {
            queriedPath = normalizedPath;
            return normalizedPath != null && normalizedPath.equals(overriddenPath);
        }

        @Override
        public InputStream openStream(final String resourcePath) {
            return null;
        }
    }
}
