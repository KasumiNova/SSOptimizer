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
 * 后台纹理压缩调度器（设计：docs/design/gpu-texture-compression.md §3.3）。
 * <p>
 * 缓存未命中的可压缩纹理在走未压缩上传的同时投递到这里：单线程低优先级守护线程
 * （{@code SSOptimizer-TexCompress}）从阻塞队列取任务，调
 * {@link NativeTextureCompressor} 压缩并写入 {@link CompressedTextureCache}；
 * 下次启动（或同会话重载）命中缓存即走压缩上传——首轮无任何帧时间风险。
 * <p>
 * 投递处把像素复制为堆内 RGBA8（源 direct buffer 上传后即允许被 GC/复用，
 * 压缩发生在很久之后，且非 alpha 纹理源缓冲是 RGB 需扩通道）；同键在队/在压即跳过。
 * 游戏退出（shutdown hook）不强等：清空队列、丢弃残留任务并记 info。
 * <p>
 * 除上传路径的像素任务外，还接收 deferred prepass「仅压缩」任务（
 * {@link #submitDeferredPrepass}）：登记延迟贴图（deferred-awaiting-first-bind）时
 * 由 LazyTextureManager 投递，worker 侧解码→转换→压缩→落盘缓存，不做 GL 上传，
 * 使首次 bind 直接命中压缩缓存；任务间有让步间隔（{@link #DEFERRED_PREPASS_PAUSE_MILLIS}），
 * 开关 {@code ssoptimizer.texcompress.deferredPrepass}（默认 true）。
 * <p>
 * native 压缩库不可用时调度器整体停用（记一次 info），未压缩路径不受影响。
 */
public final class TextureCompressionScheduler {
    static final String QUALITY_PROPERTY = "ssoptimizer.texcompress.quality";
    /**
     * 高质量路径模式（逗号分隔子串，大小写不敏感，匹配规范化后的资源路径）：
     * 命中的贴图强制 {@link NativeTextureCompressor#QUALITY_HIGH}，优先于全局质量档。
     * 默认空——背景/特效类贴图实测 high 档 BC7 仍有可见色阶，已由
     * {@code ssoptimizer.texcompress.excludePaths} 默认排除面直接绕过压缩；
     * 本属性留给「宁可压缩也要省显存」的自定义场景。
     */
    static final String HIGH_QUALITY_PATHS_PROPERTY = "ssoptimizer.texcompress.highQualityPaths";
    /** {@link #HIGH_QUALITY_PATHS_PROPERTY} 的默认模式（默认空：背景/特效走排除面而非升档）。 */
    static final String DEFAULT_HIGH_QUALITY_PATHS = "";
    /**
     * deferred prepass 开关（默认 true）：对已进入 LazyTextureManager 索引但尚未加载的
     * 贴图（deferred-awaiting-first-bind）投递「仅压缩」后台任务——解码→转换→压缩→
     * 落盘缓存，不做 GL 上传，使首次 bind 直接命中压缩缓存。
     */
    static final String DEFERRED_PREPASS_PROPERTY = "ssoptimizer.texcompress.deferredPrepass";

    private static final String THREAD_NAME = "SSOptimizer-TexCompress";

    /** deferred prepass 任务间让步间隔：解码+转换是 CPU 重活，压低节奏不抢加载关键路径。 */
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

    /** 进度汇总间隔：每完成该数量任务输出一条 info（单条完成日志仍为 debug，避免刷屏）。 */
    private static final long PROGRESS_LOG_INTERVAL = 64L;
    /** 失败详情 warn 的逐条上限：超过后只在进度汇总里体现计数，避免故障时刷屏。 */
    private static final long FAILURE_LOG_LIMIT     = 8L;

    /**
     * 压缩完成回调（热重传用，设计 §3.3）：worker 线程上、缓存写入成功后触发。
     * 由 LazyTextureManager 在类初始化时注册；未注册=无热重传（首轮压缩结果下个会话生效）。
     */
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

    /** 调度器任务公共契约：资源路径 + 压缩缓存键（IN_FLIGHT 去重与日志用）。 */
    private sealed interface SchedulerTask {
        String resourcePath();

        CompressedTextureCache.Key key();
    }

    /** 像素在手的压缩任务：键 + mip 层数 + 质量档 + 堆内 RGBA8 像素拷贝。 */
    private record CompressionTask(String resourcePath,
                                   CompressedTextureCache.Key key,
                                   int mipLevels,
                                   int quality,
                                   boolean useAlpha,
                                   byte[] rgbaPixels) implements SchedulerTask {
    }

    /**
     * deferred prepass 任务（设计 §3.3）：贴图已进入 LazyTextureManager 索引但尚未加载
     * （deferred-awaiting-first-bind），像素不在手——worker 侧从源字节解码→转换→压缩→
     * 落盘缓存，不做 GL 上传。键在投递时由登记元数据完全确定（含格式与 mip 标志）。
     */
    private record DeferredPrepassTask(String resourcePath,
                                       CompressedTextureCache.Key key,
                                       int mipLevels,
                                       int quality,
                                       boolean useAlpha) implements SchedulerTask {
    }

    /**
     * 投递压缩任务（像素在此刻复制，调用方随后可随意处置源缓冲）。
     *
     * @param pixels   上传用像素缓冲（RGBA8 或 RGB8 紧密排列，容量须等于 w*h*4 或 w*h*3）
     * @param useAlpha 仅 BC1 有效：BINARY alpha 内容的 1-bit punch-through 编码
     * @return 是否真正入队（同键在队/在压、调度器关闭、像素形态非法均为 false）
     */
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

    /**
     * 投递 deferred prepass「仅压缩」任务（登记延迟贴图时由 LazyTextureManager 调用）。
     * 像素不在手，解码/转换由 worker 完成；与像素任务共用同一低优先级队列与去重簿记。
     *
     * @return 是否真正入队（开关关闭、同键在队/在压、调度器关闭均为 false）
     */
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

    /** 同键在队/在压去重（像素任务与 prepass 任务共用同一键空间）。 */
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

    /** deferred prepass 开关（默认 true，见 {@link #DEFERRED_PREPASS_PROPERTY}）。 */
    static boolean isDeferredPrepassEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(DEFERRED_PREPASS_PROPERTY, "true"));
    }

    /** 当前质量档（投递时读取）：fast/normal/high，默认 normal，未知值按 normal。 */
    static int currentQuality() {
        return parseQuality(System.getProperty(QUALITY_PROPERTY));
    }

    /**
     * 路径分级质量解析：命中 {@link #HIGH_QUALITY_PATHS_PROPERTY} 模式（子串、大小写不敏感）
     * 的路径强制 {@link NativeTextureCompressor#QUALITY_HIGH}，否则用全局质量档。
     */
    static int resolveQuality(final String resourcePath) {
        if (pathMatchesAny(resourcePath, highQualityPathPatterns())) {
            return NativeTextureCompressor.QUALITY_HIGH;
        }
        return currentQuality();
    }

    /** 高质量路径模式（投递时读取属性，逗号分隔，去空白去空项，转小写）。 */
    static String[] highQualityPathPatterns() {
        return splitPatterns(System.getProperty(HIGH_QUALITY_PATHS_PROPERTY, DEFAULT_HIGH_QUALITY_PATHS));
    }

    /** 逗号分隔模式串解析：去空白、去空项、统一小写。 */
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

    /** 规范化路径（\ → /、小写）是否包含任一模式子串。 */
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

    /** 等待队列清空（仅测试/诊断用途）。 */
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

    /** 测试用：复位全部静态状态（计数/队列/关闭标志），下一次 submit 重启 worker。 */
    static void resetForTests() {
        closed = true; // 先拦新投递，再清场
        QUEUE.clear();
        IN_FLIGHT.clear();
        SUBMITTED.set(0L);
        COMPLETED.set(0L);
        FAILED.set(0L);
        DROPPED.set(0L);
        STARTED.set(false);
        closed = false;
    }

    /** 游戏退出：不强等——清空队列、丢弃残留任务并记 info（shutdown hook 调用）。 */
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
                // 兜底：native 侧的 Error（如 UnsatisfiedLinkError）不应杀死 worker 线程
                FAILED.incrementAndGet();
                LOGGER.error("[SSOptimizer] 纹理压缩任务异常终止（" + task.resourcePath() + "）", t);
            } finally {
                IN_FLIGHT.remove(CompressedTextureCache.keyId(task.key()));
            }
            if (task instanceof DeferredPrepassTask) {
                // prepass 的解码+转换是 CPU 重活，任务间让步一拍，不抢加载关键路径
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

    /**
     * deferred prepass 任务处理：从源字节解码→转换（顺带写 ssotex 缓存与 alphaKind
     * 元数据）→压缩→落盘压缩缓存，全程不触碰 GL。已存在压缩缓存条目时直接跳过
     * （上传路径的像素任务或前序 prepass 可能已先完成同键压缩）。
     */
    private static void processDeferredPrepass(final DeferredPrepassTask task) {
        final long startNanos = System.nanoTime();

        if (CompressedTextureCache.load(task.key()) != null) {
            LOGGER.debug("[SSOptimizer] deferred prepass 跳过（压缩缓存已存在）: " + task.resourcePath());
            return;
        }

        final byte[] rgbaPixels = decodePrepassPixels(task.resourcePath(), task.key());
        if (rgbaPixels == null) {
            return; // decodePrepassPixels 内部已记日志并计数
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

    /**
     * prepass 像素来源（包可见，测试直调）：重读源字节 → 解码 → 逐像素转换。
     * 转换走 {@link TexturePixelConverter#convert} 正式路径，ssotex 缓存与 alphaKind
     * 元数据随之落盘，首次 bind 的未压缩兜底上传同样受益。
     *
     * @return 堆内 RGBA8 像素；任一环节失败返回 null（记日志并计 FAILED）
     */
    static byte[] decodePrepassPixels(final String resourcePath,
                                      final CompressedTextureCache.Key key) {
        final byte[] sourceBytes = LazyTextureManager.readRebuildSourceBytes(resourcePath);
        if (sourceBytes == null) {
            FAILED.incrementAndGet();
            return null; // readRebuildSourceBytes 已记 ERROR
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
                TextureConversionCache.probeFingerprint(resourcePath));
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

    /** 压缩成功后的统一收尾：落盘缓存、热重传通知、进度日志。 */
    private static void completeTask(final SchedulerTask task,
                                     final int inputBytes,
                                     final byte[] container,
                                     final long startNanos) {
        CompressedTextureCache.store(task.key(), container, task.resourcePath(),
                TextureConversionCache.probeFingerprint(task.resourcePath()));
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

    /** native 压缩返回空的统一计数与限频日志（compress 内部已对异常记 warn）。 */
    private static void noteCompressionReturnedNull(final SchedulerTask task) {
        final long failures = FAILED.incrementAndGet();
        if (failures <= FAILURE_LOG_LIMIT) {
            LOGGER.warn("[SSOptimizer] native BC 压缩返回空结果（" + task.resourcePath()
                    + " " + task.key().width() + 'x' + task.key().height()
                    + ", " + task.key().format().tag() + "），按压缩不可用降级");
        }
    }

    /** 清空队列并同步 IN_FLIGHT 簿记；返回丢弃任务数。 */
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

    /**
     * 把上传用像素缓冲复制为堆内 RGBA8。源缓冲容量 w*h*4 直接拷贝；
     * w*h*3（无 alpha 纹理的 RGB 布局）扩通道补 alpha=255；其余形态返回 null。
     * 包可见：eager 同步压缩路径（LazyTextureManager）复用同一通道规整逻辑。
     */
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
