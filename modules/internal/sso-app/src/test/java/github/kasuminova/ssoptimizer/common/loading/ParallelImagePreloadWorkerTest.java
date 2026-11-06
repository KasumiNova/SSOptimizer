package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParallelImagePreloadWorkerTest {
    private static Method resolveMethod(final Class<?> owner,
                                        final String methodName,
                                        final Class<?> returnType,
                                        final Class<?>... parameterTypes) throws Exception {
        final Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        assertEquals(returnType, method.getReturnType());
        return method;
    }

    @TempDir
    Path gameDir;

    @AfterEach
    void tearDown() {
        TexturePreparationRegistry.clear();
    }

    @Test
    void fontOverridePathStillCompletesPreparationFuture() throws Exception {
        final String path = "graphics/fonts/insignia12_0.png";
        final Path png = gameDir.resolve(path);
        Files.createDirectories(png.getParent());
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB), "png", png.toFile());

        com.fs.util.ResourceLoader.getInstance().addDirectoryResource(gameDir.toAbsolutePath().toString());

        assertTrue(github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides
                        .isOverriddenPath(path),
                "测试前提：该路径必须属于字体覆盖集");

        TexturePreparationRegistry.track(path);
        com.fs.graphics.ParallelImagePreloader.enqueueImage(path);

        new ParallelImagePreloadWorker().run();

        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<TexturePreparationRegistry.Prepared> awaiting =
                    executor.submit(() -> TexturePreparationRegistry.await(path));
            final TexturePreparationRegistry.Prepared prepared = awaiting.get(5, TimeUnit.SECONDS);
            assertNull(prepared, "字体覆盖路径未走预备管线，应返回 null 让主线程回退原版路径");
        } finally {
            executor.shutdownNow();
        }

        com.fs.graphics.ParallelImagePreloader.awaitImage(path);
    }

    @Test
    void resolvesNamedLoadBytesMethod() throws Exception {
        final Method method = resolveMethod(FakeDeferredLoaderWithNamedLoader.class, "loadBytes", byte[].class, String.class);

        assertEquals("loadBytes", method.getName());
    }

    @Test
    void resolvesNamedDecodeImageMethod() throws Exception {
        final Method method = resolveMethod(FakeDeferredLoaderWithNamedLoader.class, "decodeImage", java.awt.image.BufferedImage.class, String.class);

        assertEquals("decodeImage", method.getName());
    }

    static final class FakeDeferredLoaderWithNamedLoader {
        private static byte[] loadBytes(final String path) {
            return path.getBytes();
        }

        private static java.awt.image.BufferedImage decodeImage(final String path) {
            return new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        }
    }
}