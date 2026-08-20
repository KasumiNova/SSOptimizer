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

/**
 * {@link ParallelImagePreloadWorker} 的反射入口选择回归测试。
 */
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

    /**
     * 字体覆盖路径回归：worker 走原版回写分支时也必须完结
     * {@link TexturePreparationRegistry} 中登记的 future，
     * 否则主线程 {@code LazyTextureManager.loadTexture -> await} 会永久阻塞（死锁）。
     * <p>
     * 这里用真实游戏类驱动完整 worker 流程：字体图集 png 写入临时目录并注册为
     * DIRECTORY 资源根，真实 {@code decodeImage} 读取成功后，
     * 注册表 future 必须已完结（await 立即返回 null 表示回退原版路径）。
     * 若 worker 回归漏调 complete，await 会超时使本测试失败。
     */
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

        // 模拟 mixin 入队：登记预备 future，并把路径放入真实游戏类的预加载队列
        TexturePreparationRegistry.track(path);
        com.fs.graphics.ParallelImagePreloader.enqueueImage(path);

        new ParallelImagePreloadWorker().run();

        // 直接 await 会在回归时永久阻塞，放执行器里加超时兜底
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            final Future<TexturePreparationRegistry.Prepared> awaiting =
                    executor.submit(() -> TexturePreparationRegistry.await(path));
            final TexturePreparationRegistry.Prepared prepared = awaiting.get(5, TimeUnit.SECONDS);
            assertNull(prepared, "字体覆盖路径未走预备管线，应返回 null 让主线程回退原版路径");
        } finally {
            executor.shutdownNow();
        }

        // 清理真实游戏类结果表中的残留（vanilla awaitImage 取回即移除）
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