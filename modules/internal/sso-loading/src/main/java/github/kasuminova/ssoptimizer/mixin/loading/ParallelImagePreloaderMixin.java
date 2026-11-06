package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.FastResourceImageDecoder;
import github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadCoordinator;
import github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadQueueTracker;
import github.kasuminova.ssoptimizer.common.loading.TexturePreparationRegistry;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@Mixin(targets = GameClassNames.PARALLEL_IMAGE_PRELOADER_DOTTED)
public abstract class ParallelImagePreloaderMixin {
    @Shadow(remap = false, aliases = "imageQueue")
    private static List<String> ssoptimizer$imageQueue;

    @Shadow(remap = false, aliases = "imageResults")
    private static Map<String, BufferedImage> ssoptimizer$imageResults;

    @Shadow(remap = false, aliases = "byteQueue")
    private static List<String> ssoptimizer$byteQueue;

    @Shadow(remap = false, aliases = "byteResults")
    private static Map<String, byte[]> ssoptimizer$byteResults;

    @Shadow(remap = false, aliases = "imageSentinel")
    private static BufferedImage ssoptimizer$imageSentinel;

    @Shadow(remap = false, aliases = "byteSentinel")
    private static byte[] ssoptimizer$byteSentinel;

    @Overwrite(remap = false)
    public static void start() {
        ParallelImagePreloadCoordinator.startWorkers();
    }

    @Overwrite(remap = false)
    public static void shutdown() {
        ParallelImagePreloadCoordinator.stopWorkers();
        ParallelImagePreloadQueueTracker.clearPending();
        TexturePreparationRegistry.clear();
        ssoptimizer$imageResults.clear();
        ssoptimizer$byteResults.clear();
        ssoptimizer$imageQueue.clear();
        ssoptimizer$byteQueue.clear();
    }

    @Overwrite(remap = false)
    public static void enqueueImage(final String path) {
        TexturePreparationRegistry.track(path);
        ParallelImagePreloadQueueTracker.enqueueImage(ssoptimizer$imageQueue, path);
    }

    @Overwrite(remap = false)
    public static void enqueueBytes(final String path) {
        ParallelImagePreloadQueueTracker.enqueueBytes(ssoptimizer$byteQueue, path);
    }

    @Overwrite(remap = false)
    public static byte[] awaitBytes(final String path) {
        return ParallelImagePreloadQueueTracker.awaitBytes(ssoptimizer$byteResults, path, ssoptimizer$byteSentinel);
    }

    @Overwrite(remap = false)
    public static BufferedImage awaitImage(final String path) {
        return ParallelImagePreloadQueueTracker.awaitImage(ssoptimizer$imageResults, path, ssoptimizer$imageSentinel);
    }

    @Redirect(
            method = "decodeImage",
            at = @At(
                    value = "INVOKE",
                    target = "Ljavax/imageio/ImageIO;read(Ljava/io/InputStream;)Ljava/awt/image/BufferedImage;"
            ),
            remap = false
    )
    private static BufferedImage ssoptimizer$redirectImageRead(final InputStream inputStream,
                                                               final String path) throws IOException {
        return FastResourceImageDecoder.decode(path, inputStream);
    }
}