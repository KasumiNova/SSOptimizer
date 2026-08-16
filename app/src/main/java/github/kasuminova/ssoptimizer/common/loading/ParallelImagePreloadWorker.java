package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.apache.log4j.Logger;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Replacement deferred preload worker that keeps the base-game queue/result-map
 * contract intact while pairing it with {@link ParallelImagePreloadQueueTracker}
 * to avoid repeated synchronized list contains scans on the main thread.
 */
public final class ParallelImagePreloadWorker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ParallelImagePreloadWorker.class);

    private static volatile LoaderAccess cachedAccess;

    private final LoaderAccess access;

    public ParallelImagePreloadWorker() {
        this.access = loaderAccess();
    }

    private static LoaderAccess loaderAccess() {
        LoaderAccess access = cachedAccess;
        if (access != null) {
            return access;
        }

        synchronized (ParallelImagePreloadWorker.class) {
            if (cachedAccess != null) {
                return cachedAccess;
            }

            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class<?> deferredLoaderClass = Class.forName(GameClassNames.PARALLEL_IMAGE_PRELOADER.replace('/', '.'), true, loader);

                cachedAccess = new LoaderAccess(
                        field(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.IMAGE_QUEUE),
                        field(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.IMAGE_RESULTS),
                        field(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.BYTE_QUEUE),
                        field(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.BYTE_RESULTS),
                        staticFieldValue(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.IMAGE_SENTINEL),
                        staticFieldValue(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.BYTE_SENTINEL),
                        method(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.DECODE_IMAGE, BufferedImage.class, String.class),
                        method(deferredLoaderClass, GameMemberNames.ParallelImagePreloader.LOAD_BYTES, byte[].class, String.class)
                );
                return cachedAccess;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to access deferred preload worker internals", e);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T staticFieldValue(final Class<?> owner, final String fieldName) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(final Class<?> owner, final String fieldName) throws ReflectiveOperationException {
        return staticFieldValue(owner, fieldName);
    }

    private static Method method(final Class<?> owner,
                                 final String methodName,
                                 final Class<?> returnType,
                                 final Class<?>... parameterTypes) throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        if (!returnType.isAssignableFrom(method.getReturnType())) {
            throw new NoSuchMethodException(methodName + " return type mismatch");
        }
        return method;
    }

    private static boolean isInterruptedFailure(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            if (current instanceof InvocationTargetException invocationTargetException) {
                current = invocationTargetException.getTargetException();
                continue;
            }
            current = current.getCause();
        }
        return false;
    }

    @Override
    public void run() {
        processByteQueue();
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        processImageQueue();
    }

    private void processByteQueue() {
        while (true) {
            String path = ParallelImagePreloadQueueTracker.dequeueBytes(
                    access.byteQueue(),
                    access.byteResults(),
                    access.byteSentinel()
            );
            if (path == null) {
                return;
            }

            try {
                access.byteResults().put(path, access.loadBytes(path));
            } catch (Throwable throwable) {
                access.byteResults().remove(path);
                if (isInterruptedFailure(throwable)) {
                    Thread.currentThread().interrupt();
                    ParallelImagePreloadQueueTracker.completeBytes(path);
                    return;
                }
                LOGGER.error(throwable.getMessage(), throwable);
            } finally {
                ParallelImagePreloadQueueTracker.completeBytes(path);
            }

            if (Thread.interrupted()) {
                return;
            }
        }
    }

    private void processImageQueue() {
        while (true) {
            String path = ParallelImagePreloadQueueTracker.dequeueImage(
                    access.imageQueue(),
                    access.imageResults(),
                    access.imageSentinel()
            );
            if (path == null) {
                return;
            }

            try {
                processImage(path);
            } catch (Throwable throwable) {
                access.imageResults().remove(path);
                TexturePreparationRegistry.complete(path, null);
                if (isInterruptedFailure(throwable)) {
                    Thread.currentThread().interrupt();
                    ParallelImagePreloadQueueTracker.completeImage(path);
                    return;
                }
                LOGGER.error(throwable.getMessage(), throwable);
            } finally {
                ParallelImagePreloadQueueTracker.completeImage(path);
            }

            if (Thread.interrupted()) {
                return;
            }
        }
    }

    /**
     * 处理单张图片：预备管线启用且非字体覆盖路径时，worker 完成读源/哈希/
     * 磁盘缓存写入（未命中时含逐像素转换），但发布到 {@link TexturePreparationRegistry}
     * 的只有元数据；像素始终保持 Zstd 压缩形态，直到主线程真正执行 GL 上传时
     * 才解压到 DirectBuffer。其余情况保持原版队列协议。
     */
    private void processImage(final String path) throws ReflectiveOperationException {
        if (!TexturePreparationRegistry.isEnabled() || isFontOverridePath(path)) {
            // 原版回写分支：必须同时完结预备注册表中的 future，
            // 否则主线程 TexturePreparationRegistry.await 会永久阻塞。
            // complete(null) 表示该路径未走预备管线，主线程回退原版读取。
            access.imageResults().put(path, access.loadImage(path));
            TexturePreparationRegistry.complete(path, null);
            return;
        }

        final BufferedImage image = access.loadImage(path);
        if (!(image instanceof TrackedResourceImage tracked) || tracked.sourceByteLength() < 0L) {
            TexturePreparationRegistry.complete(path, null);
            if (image != null) {
                access.imageResults().put(path, image);
            }
            return;
        }

        // 磁盘缓存命中：像素仍保持压缩形态，只发布元数据。
        final TextureConversionCache.CachedTextureMetadata cachedMetadata = tracked.cachedMetadata();
        if (cachedMetadata != null) {
            TexturePreparationRegistry.complete(path, new TexturePreparationRegistry.Prepared(
                    tracked.sourceHash(),
                    tracked.sourceByteLength(),
                    cachedMetadata));
            return;
        }

        // 未命中：worker 线程完成逐像素转换并写入磁盘缓存；转换产生的
        // DirectBuffer 随方法返回即失去引用，不会随结果滞留。
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        TexturePreparationRegistry.complete(path, new TexturePreparationRegistry.Prepared(
                tracked.sourceHash(),
                tracked.sourceByteLength(),
                TextureConversionCache.CachedTextureMetadata.of(
                        tracked.getWidth(),
                        tracked.getHeight(),
                        tracked.getColorModel().hasAlpha(),
                        result)));
    }

    private static boolean isFontOverridePath(final String path) {
        return OriginalGameFontOverrides.isEnabled()
                && OriginalGameFontOverrides.isOverriddenPath(OriginalGameFontOverrides.normalize(path));
    }

    private record LoaderAccess(List<String> imageQueue,
                                Map<String, BufferedImage> imageResults,
                                List<String> byteQueue,
                                Map<String, byte[]> byteResults,
                                BufferedImage imageSentinel,
                                byte[] byteSentinel,
                                Method loadImageMethod,
                                Method loadBytesMethod) {
        private BufferedImage loadImage(final String path) throws ReflectiveOperationException {
            return (BufferedImage) loadImageMethod.invoke(null, path);
        }

        private byte[] loadBytes(final String path) throws ReflectiveOperationException {
            return (byte[]) loadBytesMethod.invoke(null, path);
        }
    }
}