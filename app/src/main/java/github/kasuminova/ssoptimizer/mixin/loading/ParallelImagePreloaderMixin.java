package github.kasuminova.ssoptimizer.mixin.loading;

import github.kasuminova.ssoptimizer.common.loading.FastResourceImageDecoder;
import github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadCoordinator;
import github.kasuminova.ssoptimizer.common.loading.ParallelImagePreloadQueueTracker;
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

/**
 * 并行图片预加载器的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.ParallelImagePreloader}<br>
 * 注入动机：原 ASM 方案需要同时改写等待、入队与 worker 调用链，后续对私有方法语义判断
 * 容易出现回归；这里直接用 Mixin 接管公开协作入口，把并发队列协议固定到
 * {@link ParallelImagePreloadQueueTracker} 与 {@link ParallelImagePreloadCoordinator}。<br>
 * 注入效果：覆盖启动、关闭、入队、等待方法，并把图片解码改为
 * {@link FastResourceImageDecoder}，同时保留原类的私有字节加载实现供 worker 直接调用。
 */
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

    /**
     * 启动并行预加载 worker。
     *
     * @author GitHub Copilot
     * @reason 用 Mixin 直接接管并行预加载 worker 启动，避免 ASM 对生命周期方法的重复回归。
     */
    @Overwrite(remap = false)
    public static void start() {
        ParallelImagePreloadCoordinator.startWorkers();
    }

    /**
     * 关闭并行预加载 worker，并清理等待状态与结果缓存。
     *
     * @author GitHub Copilot
     * @reason 用 Mixin 直接接管关闭逻辑，统一清理队列跟踪状态，避免残留 pending 项导致卡顿。
     */
    @Overwrite(remap = false)
    public static void shutdown() {
        ParallelImagePreloadCoordinator.stopWorkers();
        ParallelImagePreloadQueueTracker.clearPending();
        ssoptimizer$imageResults.clear();
        ssoptimizer$byteResults.clear();
        ssoptimizer$imageQueue.clear();
        ssoptimizer$byteQueue.clear();
    }

    /**
     * 将图片路径加入并行预加载队列。
     *
     * @param path 资源路径
     *
     * @author GitHub Copilot
     * @reason 通过路径计数跟踪器维护待处理项，避免主线程反复对同步列表做 contains 扫描。
     */
    @Overwrite(remap = false)
    public static void enqueueImage(final String path) {
        ParallelImagePreloadQueueTracker.enqueueImage(ssoptimizer$imageQueue, path);
    }

    /**
     * 将字节资源路径加入并行预加载队列。
     *
     * @param path 资源路径
     *
     * @author GitHub Copilot
     * @reason 通过路径计数跟踪器维护待处理项，避免字节预加载在高并发下退化为线性扫描。
     */
    @Overwrite(remap = false)
    public static void enqueueBytes(final String path) {
        ParallelImagePreloadQueueTracker.enqueueBytes(ssoptimizer$byteQueue, path);
    }

    /**
     * 等待指定字节资源完成预加载。
     *
     * @param path 资源路径
     * @return 已加载的字节数组；若任务已取消或不存在则返回 {@code null}
     *
     * @author GitHub Copilot
     * @reason 改为使用并发队列跟踪器等待结果，避免原版同步列表 contains + sleep 的高开销轮询。
     */
    @Overwrite(remap = false)
    public static byte[] awaitBytes(final String path) {
        return ParallelImagePreloadQueueTracker.awaitBytes(ssoptimizer$byteResults, path, ssoptimizer$byteSentinel);
    }

    /**
     * 等待指定图片资源完成预加载。
     *
     * @param path 资源路径
     * @return 已加载的图片；若任务已取消或不存在则返回 {@code null}
     *
     * @author GitHub Copilot
     * @reason 改为使用并发队列跟踪器等待结果，减少主线程在图片预加载阶段的同步争用。
     */
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