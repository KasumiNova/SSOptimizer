package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.loading.TextureCompressionSupport.Format;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 工作线程依赖平台线程优先级与任务间让步，不得迁移到虚拟线程。
 * 提交时复制像素，源缓冲在提交后即可复用。
 */
public final class TextureCompressionScheduler {
    static final String QUALITY_PROPERTY = "ssoptimizer.texcompress.quality";
    static final String HIGH_QUALITY_PATHS_PROPERTY = "ssoptimizer.texcompress.highQualityPaths";
    static final String DEFAULT_HIGH_QUALITY_PATHS = "";
    static final String DEFERRED_PREPASS_PROPERTY = "ssoptimizer.texcompress.deferredPrepass";

    private static final String THREAD_NAME = "SSOptimizer-TexCompress";

    private static final long DEFERRED_PREPASS_PAUSE_MILLIS = 25L;

    private static final Logger                    LOGGER         = Logger.getLogger(TextureCompressionScheduler.class);
    private static final BlockingQueue<SchedulerTask> QUEUE       = new LinkedBlockingQueue<>();
    private static final Set<String>               IN_FLIGHT      = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean             STARTED        = new AtomicBoolean(false);
    private static final AtomicBoolean             HOOK_REGISTERED = new AtomicBoolean(false);
    private static final AtomicLong                SUBMITTED      = new AtomicLong();
    private static final AtomicLong                COMPLETED      = new AtomicLong();
    private static final AtomicLong                FAILED         = new AtomicLong();
    private static final AtomicLong                DROPPED        = new AtomicLong();

    private static final long PROGRESS_LOG_INTERVAL = 64L;
    private static final long FAILURE_LOG_LIMIT     = 8L;

    interface CompressionCompletionListener {
        void onCompressionCompleted(String resourcePath, CompressedTextureCache.Key key);
    }

    private static volatile CompressionCompletionListener completionListener;

    static void setCompletionListener(final CompressionCompletionListener listener) {
        completionListener = listener;
    }

    private static volatile boolean closed = false;

    private TextureCompressionScheduler() {
    }

    private sealed interface SchedulerTask {
        String resourcePath();

        CompressedTextureCache.Key key();
    }

    private record CompressionTask(String resourcePath,
                                   CompressedTextureCache.Key key,
                                   int mipLevels,
                                   int quality,
                                   boolean useAlpha,
                                   byte[] rgbaPixels) implements SchedulerTask {
    }

    private record DeferredPrepassTask(String resourcePath,
                                       CompressedTextureCache.Key key,
                                       int mipLevels,
                                       int quality,
                                       boolean useAlpha) implements SchedulerTask {
    }

    static boolean submit(final String resourcePath,
                          final String sourceHash,
                          final int width,
                          final int height,
                          final boolean mipmaps,
                          final Format format,
                          final boolean useAlpha,
                          final ByteBuffer pixels) {
        if (closed || format == null || format == Format.NONE
                || sourceHash == null || sourceHash.isBlank()) {
            return false;
        }

        final byte[] rgbaPixels = copyAsRgba8(pixels, width, height);
        if (rgbaPixels == null) {
            LOGGER.warn("[SSOptimizer] 纹理像素缓冲形态非法（" + resourcePath + " " + width + 'x' + height
                    + "），跳过压缩投递");
            return false;
        }

        final int quality = resolveQuality(resourcePath);
        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key(sourceHash, width, height, mipmaps, format, quality);
        if (!markInFlight(key)) {
            return false;
        }

        SUBMITTED.incrementAndGet();
        QUEUE.offer(new CompressionTask(resourcePath, key,
                mipmaps ? SsobcContainer.fullChainLevels(width, height) : 1,
                quality, useAlpha, rgbaPixels));
        ensureStarted();
        logFirstSubmission(resourcePath, width, height, format);
        return true;
    }

    static boolean submitDeferredPrepass(final String resourcePath,
                                         final String sourceHash,
                                         final int width,
                                         final int height,
                                         final boolean mipmaps,
                                         final Format format,
                                         final boolean useAlpha) {
        if (!isDeferredPrepassEnabled() || closed || format == null || format == Format.NONE
                || sourceHash == null || sourceHash.isBlank()) {
            return false;
        }

        final int quality = resolveQuality(resourcePath);
        final CompressedTextureCache.Key key =
                new CompressedTextureCache.Key(sourceHash, width, height, mipmaps, format, quality);
        if (!markInFlight(key)) {
            return false;
        }

        SUBMITTED.incrementAndGet();
        QUEUE.offer(new DeferredPrepassTask(resourcePath, key,
                mipmaps ? SsobcContainer.fullChainLevels(width, height) : 1,
                quality, useAlpha));
        ensureStarted();
        logFirstSubmission(resourcePath, width, height, format);
        return true;
    }

    private static boolean markInFlight(final CompressedTextureCache.Key key) {
        return IN_FLIGHT.add(CompressedTextureCache.keyId(key));
    }

    private static void logFirstSubmission(final String resourcePath,
                                           final int width,
                                           final int height,
                                           final Format format) {
        if (SUBMITTED.get() == 1L) {
            LOGGER.info("[SSOptimizer] 后台纹理压缩调度器已启动，首个任务: " + resourcePath
                    + " (" + width + 'x' + height + ", " + format.tag() + ')');
        }
    }

    static boolean isDeferredPrepassEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(DEFERRED_PREPASS_PROPERTY, "true"));
    }

    static int currentQuality() {
        return parseQuality(System.getProperty(QUALITY_PROPERTY));
    }

    static int resolveQuality(final String resourcePath) {
        if (pathMatchesAny(resourcePath, highQualityPathPatterns())) {
            return NativeTextureCompressor.QUALITY_HIGH;
        }
        return currentQuality();
    }

    static String[] highQualityPathPatterns() {
        return splitPatterns(System.getProperty(HIGH_QUALITY_PATHS_PROPERTY, DEFAULT_HIGH_QUALITY_PATHS));
    }

    static String[] splitPatterns(final String value) {
        if (value == null || value.isBlank()) {
            return new String[0];
        }
        final String[] parts = value.split(",");
        final List<String> patterns = new ArrayList<>(parts.length);
        for (final String part : parts) {
            final String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                patterns.add(trimmed);
            }
        }
        return patterns.toArray(new String[0]);
    }

    static boolean pathMatchesAny(final String resourcePath, final String[] patterns) {
        if (resourcePath == null || patterns.length == 0) {
            return false;
        }
        final String normalized = resourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        for (final String pattern : patterns) {
            if (normalized.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    static int parseQuality(final String value) {
        final String normalized = value == null ? "normal" : value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fast" -> NativeTextureCompressor.QUALITY_FAST;
            case "high" -> NativeTextureCompressor.QUALITY_HIGH;
            default -> NativeTextureCompressor.QUALITY_NORMAL;
        };
    }

    static boolean awaitIdle(final long timeoutMillis) {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (QUEUE.isEmpty() && IN_FLIGHT.isEmpty()) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return QUEUE.isEmpty() && IN_FLIGHT.isEmpty();
    }

    static long submittedCount() {
        return SUBMITTED.get();
    }

    static long completedCount() {
        return COMPLETED.get();
    }

    static long failedCount() {
        return FAILED.get();
    }

    static long droppedCount() {
        return DROPPED.get();
    }

    static void resetForTests() {
        closed = true;
        QUEUE.clear();
        IN_FLIGHT.clear();
        SUBMITTED.set(0L);
        COMPLETED.set(0L);
        FAILED.set(0L);
        DROPPED.set(0L);
        STARTED.set(false);
        closed = false;
    }

    static void shutdown() {
        closed = true;
        final Thread worker = workerThread;
        if (worker != null) {
            worker.interrupt();
        }
        final int dropped = drainQueue();
        if (dropped > 0) {
            LOGGER.info("[SSOptimizer] 游戏退出，丢弃 " + dropped + " 个未完成的纹理压缩任务");
        }
    }

    private static volatile Thread workerThread;

    private static void ensureStarted() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        if (HOOK_REGISTERED.compareAndSet(false, true)) {
            // JVM 关停阶段虚拟线程调度器已停用，shutdown hook 必须使用平台线程。
            Runtime.getRuntime().addShutdownHook(new Thread(TextureCompressionScheduler::shutdown));
        }
        final Thread worker = new Thread(TextureCompressionScheduler::runWorker, THREAD_NAME);
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        workerThread = worker;
        worker.start();
    }

    private static void runWorker() {
        if (!NativeTextureCompressor.isAvailable()) {
            closed = true;
            final int dropped = drainQueue();
            LOGGER.info("[SSOptimizer] native BC 压缩器不可用，后台压缩调度器停用（丢弃 "
                    + dropped + " 个待压任务），纹理解码/上传不受影响");
            return;
        }

        while (!closed) {
            final SchedulerTask task;
            try {
                task = QUEUE.poll(200L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (task == null) {
                continue;
            }
            try {
                if (task instanceof CompressionTask compressionTask) {
                    process(compressionTask);
                } else {
                    processDeferredPrepass((DeferredPrepassTask) task);
                }
            } catch (Throwable t) {
                // native Error 不得终止 worker。
                FAILED.incrementAndGet();
                LOGGER.error("[SSOptimizer] 纹理压缩任务异常终止（" + task.resourcePath() + "）", t);
            } finally {
                IN_FLIGHT.remove(CompressedTextureCache.keyId(task.key()));
            }
            if (task instanceof DeferredPrepassTask) {
                try {
                    Thread.sleep(DEFERRED_PREPASS_PAUSE_MILLIS);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        final int dropped = drainQueue();
        if (dropped > 0) {
            LOGGER.info("[SSOptimizer] 纹理压缩调度器停止，丢弃 " + dropped + " 个未完成的任务");
        }
    }

    private static void process(final CompressionTask task) {
        final long startNanos = System.nanoTime();

        final ByteBuffer pixels = BufferUtils.createByteBuffer(task.rgbaPixels().length);
        pixels.put(task.rgbaPixels());
        pixels.flip();

        final byte[] container = NativeTextureCompressor.compress(
                task.key().format().nativeId(), pixels,
                task.key().width(), task.key().height(), task.mipLevels(), task.quality(),
                task.useAlpha());
        if (container == null) {
            noteCompressionReturnedNull(task);
            return;
        }

        completeTask(task, task.rgbaPixels().length, container, startNanos);
    }

    private static void processDeferredPrepass(final DeferredPrepassTask task) {
        final long startNanos = System.nanoTime();

        if (CompressedTextureCache.load(task.key()) != null) {
            LOGGER.debug("[SSOptimizer] deferred prepass 跳过（压缩缓存已存在）: " + task.resourcePath());
            return;
        }

        final byte[] rgbaPixels = decodePrepassPixels(task.resourcePath(), task.key());
        if (rgbaPixels == null) {
            return;
        }

        final ByteBuffer pixels = BufferUtils.createByteBuffer(rgbaPixels.length);
        pixels.put(rgbaPixels);
        pixels.flip();

        final byte[] container = NativeTextureCompressor.compress(
                task.key().format().nativeId(), pixels,
                task.key().width(), task.key().height(), task.mipLevels(), task.quality(),
                task.useAlpha());
        if (container == null) {
            noteCompressionReturnedNull(task);
            return;
        }

        completeTask(task, rgbaPixels.length, container, startNanos);
    }

    static byte[] decodePrepassPixels(final String resourcePath,
                                      final CompressedTextureCache.Key key) {
        final byte[] sourceBytes = LazyTextureManager.readRebuildSourceBytes(resourcePath);
        if (sourceBytes == null) {
            FAILED.incrementAndGet();
            return null;
        }

        final String actualHash = TrackedResourceImage.computeSourceHash(sourceBytes);
        if (!actualHash.equals(key.sourceHash())) {
            FAILED.incrementAndGet();
            LOGGER.warn("[SSOptimizer] deferred prepass 源哈希与登记键不一致（" + resourcePath
                    + ": registered=" + key.sourceHash() + ", actual=" + actualHash + "），跳过压缩");
            return null;
        }

        final java.awt.image.BufferedImage decoded;
        try {
            decoded = FastResourceImageDecoder.decodeUntracked(sourceBytes);
        } catch (java.io.IOException e) {
            FAILED.incrementAndGet();
            LOGGER.warn("[SSOptimizer] deferred prepass 解码失败（" + resourcePath + "）", e);
            return null;
        }
        if (decoded == null) {
            FAILED.incrementAndGet();
            LOGGER.warn("[SSOptimizer] deferred prepass 解码无结果（" + resourcePath + "），跳过压缩");
            return null;
        }

        final java.awt.image.BufferedImage tracked = TrackedResourceImage.wrap(
                resourcePath, actualHash, decoded,
                LazyTextureManager.probeManagedFingerprint(resourcePath, resourcePath));
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        if (result.textureWidth() != key.width() || result.textureHeight() != key.height()) {
            FAILED.incrementAndGet();
            LOGGER.warn("[SSOptimizer] deferred prepass 转换尺寸与登记键不一致（" + resourcePath
                    + ": key=" + key.width() + 'x' + key.height()
                    + ", actual=" + result.textureWidth() + 'x' + result.textureHeight() + "），跳过压缩");
            return null;
        }

        final byte[] rgbaPixels = copyAsRgba8(result.buffer(), result.textureWidth(), result.textureHeight());
        if (rgbaPixels == null) {
            FAILED.incrementAndGet();
            LOGGER.warn("[SSOptimizer] deferred prepass 像素缓冲形态非法（" + resourcePath + "），跳过压缩");
        }
        return rgbaPixels;
    }

    private static void completeTask(final SchedulerTask task,
                                     final int inputBytes,
                                     final byte[] container,
                                     final long startNanos) {
        CompressedTextureCache.store(task.key(), container, task.resourcePath(),
                LazyTextureManager.probeManagedFingerprint(task.resourcePath(), task.resourcePath()));
        final long completed = COMPLETED.incrementAndGet();

        final CompressionCompletionListener listener = completionListener;
        if (listener != null) {
            listener.onCompressionCompleted(task.resourcePath(), task.key());
        }

        final long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
        LOGGER.debug("[SSOptimizer] 纹理压缩完成 " + task.resourcePath()
                + ": " + inputBytes + " -> " + container.length + " bytes, "
                + elapsedMillis + " ms");
        if (completed == 1L || completed % PROGRESS_LOG_INTERVAL == 0L) {
            LOGGER.info("[SSOptimizer] 纹理压缩进度：已完成 " + completed
                    + "，失败 " + FAILED.get() + "，在队 " + QUEUE.size());
        }
    }

    private static void noteCompressionReturnedNull(final SchedulerTask task) {
        final long failures = FAILED.incrementAndGet();
        if (failures <= FAILURE_LOG_LIMIT) {
            LOGGER.warn("[SSOptimizer] native BC 压缩返回空结果（" + task.resourcePath()
                    + " " + task.key().width() + 'x' + task.key().height()
                    + ", " + task.key().format().tag() + "），按压缩不可用降级");
        }
    }

    private static int drainQueue() {
        int dropped = 0;
        SchedulerTask task;
        while ((task = QUEUE.poll()) != null) {
            IN_FLIGHT.remove(CompressedTextureCache.keyId(task.key()));
            dropped++;
        }
        if (dropped > 0) {
            DROPPED.addAndGet(dropped);
        }
        return dropped;
    }

    static byte[] copyAsRgba8(final ByteBuffer pixels,
                                      final int width,
                                      final int height) {
        if (pixels == null) {
            return null;
        }
        final long pixelCount = (long) width * height;
        final int capacity = pixels.capacity();
        final ByteBuffer source = pixels.duplicate();

        if (capacity == pixelCount * 4L) {
            final byte[] rgba = new byte[capacity];
            source.position(0);
            source.get(rgba);
            return rgba;
        }
        if (capacity == pixelCount * 3L) {
            final byte[] rgba = new byte[(int) (pixelCount * 4L)];
            source.position(0);
            for (int i = 0; i < pixelCount; i++) {
                rgba[i * 4]     = source.get();
                rgba[i * 4 + 1] = source.get();
                rgba[i * 4 + 2] = source.get();
                rgba[i * 4 + 3] = (byte) 0xFF;
            }
            return rgba;
        }
        return null;
    }
}
