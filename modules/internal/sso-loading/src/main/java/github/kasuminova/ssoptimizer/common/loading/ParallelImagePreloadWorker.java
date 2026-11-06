package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class ParallelImagePreloadWorker implements Runnable {
    private static final Logger LOGGER = Logger.getLogger(ParallelImagePreloadWorker.class);

    private static volatile LoaderAccess cachedAccess;
    private static volatile FontResourceOverride fontResourceOverrideForTests;

    private final LoaderAccess access;

    public ParallelImagePreloadWorker() {
        final LoaderAccess installed = cachedAccess;
        if (installed == null) {
            throw new IllegalStateException("Parallel image preload access is not installed");
        }
        this.access = installed;
    }

    public static void installAccess(final List<String> imageQueue,
                                     final Map<String, BufferedImage> imageResults,
                                     final List<String> byteQueue,
                                     final Map<String, byte[]> byteResults,
                                     final BufferedImage imageSentinel,
                                     final byte[] byteSentinel,
                                     final ImageLoader imageLoader,
                                     final ByteLoader byteLoader) {
        cachedAccess = new LoaderAccess(imageQueue, imageResults, byteQueue, byteResults,
                imageSentinel, byteSentinel, imageLoader, byteLoader);
    }

    static void resetAccessForTests() {
        cachedAccess = null;
    }

    static void installFontResourceOverrideForTests(final FontResourceOverride fontResourceOverride) {
        if (fontResourceOverride == null) {
            throw new IllegalArgumentException("fontResourceOverride must not be null");
        }
        fontResourceOverrideForTests = fontResourceOverride;
    }

    static void resetFontResourceOverrideForTests() {
        fontResourceOverrideForTests = null;
    }

    private static boolean isInterruptedFailure(final Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
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
            } catch (Exception exception) {
                access.byteResults().remove(path);
                if (isInterruptedFailure(exception)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                LOGGER.error("[SSOptimizer] Image preload byte load failed for " + path, exception);
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
            } catch (Exception exception) {
                access.imageResults().remove(path);
                TexturePreparationRegistry.complete(path, null);
                if (isInterruptedFailure(exception)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                LOGGER.error("[SSOptimizer] Image preload decode failed for " + path, exception);
            } finally {
                ParallelImagePreloadQueueTracker.completeImage(path);
            }

            if (Thread.interrupted()) {
                return;
            }
        }
    }

    private void processImage(final String path) throws IOException {
        if (!TexturePreparationRegistry.isEnabled() || isFontOverridePath(path)) {
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

        final TextureConversionCache.CachedTextureMetadata cachedMetadata = tracked.cachedMetadata();
        if (cachedMetadata != null) {
            TexturePreparationRegistry.complete(path, new TexturePreparationRegistry.Prepared(
                    tracked.sourceHash(),
                    tracked.sourceByteLength(),
                    cachedMetadata));
            return;
        }

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
        final FontResourceOverride fontOverrideService = fontResourceOverride();
        return fontOverrideService != null
                && fontOverrideService.isEnabled()
                && fontOverrideService.isOverriddenPath(fontOverrideService.normalize(path));
    }

    @FunctionalInterface
    public interface ImageLoader {
        BufferedImage load(String path) throws IOException;
    }

    @FunctionalInterface
    public interface ByteLoader {
        byte[] load(String path) throws IOException;
    }

    private record LoaderAccess(List<String> imageQueue,
                                Map<String, BufferedImage> imageResults,
                                List<String> byteQueue,
                                Map<String, byte[]> byteResults,
                                BufferedImage imageSentinel,
                                byte[] byteSentinel,
                                ImageLoader imageLoader,
                                ByteLoader byteLoader) {
        private BufferedImage loadImage(final String path) throws IOException {
            return imageLoader.load(path);
        }

        private byte[] loadBytes(final String path) throws IOException {
            return byteLoader.load(path);
        }
    }

    private static FontResourceOverride fontResourceOverride() {
        final FontResourceOverride installed = fontResourceOverrideForTests;
        return installed != null ? installed : ServiceRegistry.getOrNull(FontResourceOverride.class);
    }
}
