package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

/**
 * 贴图预备结果注册表。
 * <p>
 * 预加载 worker 在后台线程完成「读源 + 哈希 + 磁盘缓存写入/命中」，
 * 发布的结果仅含元数据（尺寸/alpha/直方图颜色），像素始终保持 Zstd 压缩形态
 * 滞留在磁盘/堆内压缩缓存中，直到主线程真正执行 GL 上传时才解压到
 * DirectBuffer，避免全部贴图预先解压吃满直接内存。
 * 被延迟（defer）的贴图也不再向原版 imageResults 回写解码图，避免大量
 * BufferedImage 在堆内滞留到加载阶段结束。
 * <p>
 * 仅在 {@link TextureConversionCache} 启用时工作；{@code -Dssoptimizer.disable.texturepreparation=true}
 * 可整体关闭，此时所有 {@link #await} 立即返回 null，调用方回退原版读取路径。
 */
public final class TexturePreparationRegistry {
    private static final Logger LOGGER = Logger.getLogger(TexturePreparationRegistry.class);

    /** 全局禁用开关。 */
    public static final String DISABLE_PROPERTY = "ssoptimizer.disable.texturepreparation";

    private static final ConcurrentMap<String, CompletableFuture<Prepared>> PENDING = new ConcurrentHashMap<>();

    private TexturePreparationRegistry() {
    }

    /**
     * worker 预备完成后的结果。
     *
     * @param sourceHash 源字节 SHA-256
     * @param sourceByteLength 源文件字节数
     * @param metadata 尺寸/alpha/直方图颜色等元数据（不含像素缓冲区）
     */
    public record Prepared(String sourceHash,
                           long sourceByteLength,
                           TextureConversionCache.CachedTextureMetadata metadata) {
    }

    /** 预备管线是否启用。 */
    public static boolean isEnabled() {
        return TextureConversionCache.isEnabled() && !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    /**
     * 入队时登记一个待预备的路径。
     */
    public static void track(final String path) {
        if (path == null || !isEnabled()) {
            return;
        }
        PENDING.putIfAbsent(path, new CompletableFuture<>());
    }

    /**
     * worker 完成预备后发布结果。
     *
     * @param prepared 预备结果；null 表示该路径未走预备管线（主线程应回退原版路径）
     */
    public static void complete(final String path, final Prepared prepared) {
        if (path == null) {
            return;
        }
        final CompletableFuture<Prepared> future = PENDING.get(path);
        if (future != null) {
            future.complete(prepared);
        }
    }

    /**
     * 主线程等待并取回预备结果（取回后即从注册表移除）。
     *
     * @return 预备结果；路径未登记、预备被跳过或等待被中断时返回 null
     */
    public static Prepared await(final String path) {
        if (path == null || !isEnabled()) {
            return null;
        }

        final CompletableFuture<Prepared> future = PENDING.get(path);
        if (future == null) {
            return null;
        }

        try {
            return future.get();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (final ExecutionException e) {
            LOGGER.warn("[SSOptimizer] Texture preparation failed for " + path + ": " + e.getCause());
            return null;
        } finally {
            PENDING.remove(path);
        }
    }

    /** 加载阶段结束时清理全部待处理项。 */
    public static void clear() {
        PENDING.clear();
    }
}
