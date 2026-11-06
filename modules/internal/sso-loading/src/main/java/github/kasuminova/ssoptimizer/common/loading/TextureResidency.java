package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.mixin.loading.TextureObjectAccessor;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

final class TextureResidency {
    private static final Logger LOGGER = Logger.getLogger(TextureResidency.class);
    private static final int TARGET_2D = 3553;
    private static final WeakKeyMap<com.fs.graphics.TextureObject, ManagedTextureEntry> MANAGED_TEXTURES =
            new WeakKeyMap<>();
    private static final AtomicBoolean COMPOSITION_REPORT_HOOK_INSTALLED = new AtomicBoolean(false);
    private static final AtomicLong TOTAL_EVICTED_TEXTURES = new AtomicLong();
    private static final AtomicLong PENDING_EVICTED_TEXTURES = new AtomicLong();
    private static final AtomicLong HOT_RELOADED_TEXTURES = new AtomicLong();
    private static final Queue<PendingCompressionUpgrade> PENDING_COMPRESSION_UPGRADES = new ConcurrentLinkedQueue<>();
    private static final long HOT_RELOAD_DRAIN_INTERVAL_NANOS = 5_000_000L;
    private static final int HOT_RELOAD_DRAIN_BUDGET = 8;
    private static volatile long nextSweepNanos = 0L;
    private static volatile long nextCompositionReportNanos = 0L;
    private static volatile long nextManagementLogNanos = 0L;
    private static volatile long nextHotReloadDrainNanos = 0L;

    private TextureResidency() {
    }

    static void installCompositionReportHookIfConfigured() {
        final String configured = TextureResidencyPolicy.configuredCompositionReportPath();
        if (!COMPOSITION_REPORT_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("[SSOptimizer] Texture composition TSV export enabled: " + TextureManagementReport.resolveReportPath(configured));

        // JVM 关停阶段虚拟线程调度器已停用，shutdown hook 必须使用平台线程。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                final Path exported = exportTextureCompositionReport(configured);
                LOGGER.info("[SSOptimizer] Exported texture composition report to " + exported);
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("[SSOptimizer] Failed to export texture composition report", e);
            }
        }, "SSOptimizer-TextureCompositionReport"));
    }

    static Path exportTextureCompositionReport(final String outputPath) throws IOException {
        return TextureManagementReport.exportTextureCompositionReport(snapshotTrackedTextures(), outputPath, Instant.now());
    }

    static boolean isTextureEvictable(final com.fs.graphics.TextureObject texture) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        return entry != null && entry.evictable;
    }

    static void trackResidentTextureForTests(final com.fs.graphics.TextureObject texture,
                                             final String resourcePath) {
        if (texture == null) {
            return;
        }
        final TextureResourceLoader.LazyTextureMetadata metadata = new TextureResourceLoader.LazyTextureMetadata(
                64,
                64,
                true,
                AlphaKind.FULL,
                64,
                64,
                Color.BLACK,
                Color.BLACK,
                Color.BLACK,
                64L * 64L * 4L
        );
        MANAGED_TEXTURES.put(texture, ManagedTextureEntry.resident(
                TextureResourceLoader.normalizeResourcePath(resourcePath),
                "test-hash",
                metadata,
                System.nanoTime(),
                true));
    }

    static int managedTextureCountForTests() {
        return MANAGED_TEXTURES.size();
    }

    static CompressedTextureCache.Key compressedUpgradeKeyForTests(final com.fs.graphics.TextureObject texture) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        return entry == null ? null : entry.compressedUpgradeKey;
    }

    static void maybeSweepIdleTextures(final com.fs.graphics.TextureObject currentTexture,
                                       final long now) {
        final long idleMillis = TextureResidencyPolicy.idleUnloadMillis();
        if (idleMillis <= 0L || MANAGED_TEXTURES.isEmpty()) {
            return;
        }

        final long sweepAt = nextSweepNanos;
        if (now < sweepAt) {
            return;
        }
        nextSweepNanos = now + TextureResidencyPolicy.sweepIntervalMillis() * 1_000_000L;

        final int[] evicted = {0};
        synchronized (MANAGED_TEXTURES) {
            MANAGED_TEXTURES.forEach((candidate, entry) -> {
                if (candidate == null || candidate == currentTexture) {
                    return;
                }
                if (entry == null || entry.pendingUpload()) {
                    return;
                }
                if (!entry.evictable) {
                    return;
                }

                final int textureId = readTextureId(candidate, -1);
                if (textureId == -1) {
                    entry.markPendingUpload();
                    return;
                }
                final long candidateIdleNanos = TextureResidencyPolicy.effectiveIdleUnloadMillis(entry.resourcePath) * 1_000_000L;
                if (now - entry.lastBindNanos() < candidateIdleNanos) {
                    return;
                }

                GL11.glDeleteTextures(textureId);
                setTextureId(candidate, -1);
                entry.markPendingUpload();
                evicted[0]++;
            });
        }

        if (evicted[0] > 0) {
            TOTAL_EVICTED_TEXTURES.addAndGet(evicted[0]);
            PENDING_EVICTED_TEXTURES.addAndGet(evicted[0]);
            LOGGER.debug("[SSOptimizer] Evicted " + evicted[0] + " idle texture(s) from VRAM");
        }
    }

    private static void uploadDeferredTexture(final com.fs.graphics.TextureObject texture,
                                              final int target,
                                              final ManagedTextureEntry entry) throws IOException {
        final LazyTextureManager.ResolvedDeferredTexture resolved =
                TextureResourceLoader.resolveDeferredTextureData(entry.resourcePath, entry.sourceHash);
        if (resolved == null) {
            uploadFallbackPixelTexture(texture, target, entry);
            return;
        }

        if (!resolved.sourceHash().equals(entry.sourceHash)) {
            // 重建哈希变化后必须同步 entry 键，否则后续缓存查找仍使用旧键。
            entry.updateSourceHash(resolved.sourceHash());
        }

        final TextureConversionCache.CachedTextureData cached = resolved.data();
        final TexturePixelConversionResult result = cached.conversionResult();
        TextureResourceLoader.applyMetadata(texture, TextureResourceLoader.metadataFrom(entry.resourcePath,
                cached.imageWidth(),
                cached.imageHeight(),
                cached.hasAlpha(),
                result));

        final TextureCompressionSupport.Format usedFormat =
                TextureUploader.uploadConverted(texture, target, entry.resourcePath, resolved.sourceHash(), cached);
        entry.applyCompressedFormat(usedFormat);
    }

    private static void uploadFallbackPixelTexture(final com.fs.graphics.TextureObject texture,
                                                   final int target,
                                                   final ManagedTextureEntry entry) {
        LOGGER.error("[SSOptimizer] Deferred texture data unavailable for " + entry.resourcePath
                + ", uploading 1x1 white fallback texture");
        final BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        final TexturePixelConversionResult result = TexturePixelConverter.convert(white);
        TextureResourceLoader.applyMetadata(texture,
                TextureResourceLoader.metadataFrom(entry.resourcePath, 1, 1, true, result));
        TextureUploader.uploadConverted(texture, target, entry.resourcePath, null,
                new TextureConversionCache.CachedTextureData(1, 1, true, result));
    }

    static void noteCompressedTextureAvailable(final String resourcePath,
                                               final CompressedTextureCache.Key key) {
        if (!TextureResidencyPolicy.isHotReloadEnabled() || key == null) {
            return;
        }
        final String normalized = TextureResourceLoader.normalizeResourcePath(resourcePath);
        synchronized (MANAGED_TEXTURES) {
            MANAGED_TEXTURES.forEach((texture, entry) -> {
                if (texture == null || entry == null) {
                    return;
                }
                if (entry.pendingUpload()
                        || entry.compressionFormat != TextureCompressionSupport.Format.NONE
                        || !entry.resourcePath.equals(normalized)
                        || !entry.sourceHash.equals(key.sourceHash())) {
                    return;
                }
                if (entry.compressedUpgradeKey == null) {
                    entry.compressedUpgradeKey = key;
                    PENDING_COMPRESSION_UPGRADES.add(new PendingCompressionUpgrade(texture, key));
                }
            });
        }
    }

    // 热重传后必须恢复调用方绑定；渲染分离下逐次 capture 为空转。
    static void drainCompressionUpgrades(final int target,
                                         final int expectedBinding,
                                         final long now) {
        if (PENDING_COMPRESSION_UPGRADES.isEmpty() || now < nextHotReloadDrainNanos) {
            return;
        }
        nextHotReloadDrainNanos = now + HOT_RELOAD_DRAIN_INTERVAL_NANOS;
        if (!TextureContextTracker.hasCurrentOpenGlContext()) {
            return;
        }

        boolean upgradedAny = false;
        int budget = HOT_RELOAD_DRAIN_BUDGET;
        while (budget-- > 0) {
            final PendingCompressionUpgrade upgrade = PENDING_COMPRESSION_UPGRADES.poll();
            if (upgrade == null) {
                break;
            }
            final ManagedTextureEntry entry = MANAGED_TEXTURES.get(upgrade.texture());
            if (entry == null) {
                continue;
            }
            synchronized (entry) {
                if (entry.compressedUpgradeKey != upgrade.key()) {
                    continue;
                }
                if (entry.pendingUpload()) {
                    entry.compressedUpgradeKey = null;
                    continue;
                }
                maybeUpgradeToCompressed(upgrade.texture(), TARGET_2D, entry, false);
                upgradedAny = true;
            }
        }
        if (upgradedAny) {
            GL11.glBindTexture(target, expectedBinding);
        }
    }

    static int pendingCompressionUpgradeCountForTests() {
        return PENDING_COMPRESSION_UPGRADES.size();
    }

    private static void maybeUpgradeToCompressed(final com.fs.graphics.TextureObject texture,
                                                 final int target,
                                                 final ManagedTextureEntry entry,
                                                 final boolean restoreBinding) {
        final CompressedTextureCache.Key key = entry.compressedUpgradeKey;
        if (key == null) {
            return;
        }
        if (!entry.sourceHash.equals(key.sourceHash())) {
            entry.compressedUpgradeKey = null;
            return;
        }
        if (!TextureContextTracker.hasCurrentOpenGlContext()) {
            return;
        }
        final SsobcContainer container = CompressedTextureCache.load(key);
        final int textureId = readTextureId(texture, -1);
        if (container == null || textureId == -1) {
            entry.compressedUpgradeKey = null;
            return;
        }

        final int previousBinding = restoreBinding
                ? TextureContextTracker.captureBoundTexture(target) : Integer.MIN_VALUE;
        try {
            GL11.glBindTexture(target, textureId);
            TextureUploader.uploadCompressedLevels(target, entry.resourcePath, key.mipmaps(), container);
            entry.applyCompressedFormat(key.format());
            entry.compressedUpgradeKey = null;
            final long reloaded = HOT_RELOADED_TEXTURES.incrementAndGet();
            if (reloaded == 1L || reloaded % 64L == 0L) {
                LOGGER.info("[SSOptimizer] 纹理热重传进度：已原地升级为压缩形态 " + reloaded + " 张");
            }
        } finally {
            if (restoreBinding) {
                TextureContextTracker.restoreBoundTexture(target, previousBinding);
            }
        }
    }

    static int ensureTextureReady(final com.fs.graphics.TextureObject texture,
                                  final int target,
                                  final long now,
                                  final boolean restoreBinding) {
        final long contextGeneration = TextureContextTracker.observeCurrentOpenGlContextGeneration();
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry == null) {
            if (TextureContextTracker.requiresContextReload(texture, contextGeneration)) {
                TextureContextTracker.reloadTextureForCurrentContext(
                        texture, target, now, restoreBinding, contextGeneration);
            }
            return readTextureId(texture, -1);
        }

        entry.touch(now);
        synchronized (entry) {
            if (TextureContextTracker.requiresContextReload(texture, contextGeneration)) {
                TextureContextTracker.reloadTextureForCurrentContext(
                        texture, target, now, restoreBinding, contextGeneration);
                return readTextureId(texture, -1);
            }

            if (entry.pendingUpload()) {
                if (!TextureContextTracker.hasCurrentOpenGlContext()) {
                    LOGGER.debug("[SSOptimizer] Skipped deferred texture upload without current OpenGL context for "
                            + entry.resourcePath);
                    return readTextureId(texture, -1);
                }

                final int previousBinding = restoreBinding
                        ? TextureContextTracker.captureBoundTexture(target) : Integer.MIN_VALUE;
                try {
                    uploadDeferredTexture(texture, target, entry);
                    entry.markResident(now);
                } catch (IOException e) {
                    LOGGER.error("[SSOptimizer] Deferred texture upload failed for " + entry.resourcePath, e);
                } finally {
                    if (restoreBinding) {
                        TextureContextTracker.restoreBoundTexture(target, previousBinding);
                    }
                }
            } else if (entry.compressedUpgradeKey != null) {
                maybeUpgradeToCompressed(texture, target, entry, restoreBinding);
            }
        }

        return readTextureId(texture, -1);
    }

    static void noteTextureIdExternalized(final com.fs.graphics.TextureObject texture,
                                          final long now) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry == null) {
            return;
        }

        entry.touch(now);
        entry.markNonEvictable();
    }

    static void trackResident(final com.fs.graphics.TextureObject texture,
                              final String resourcePath,
                              final String sourceHash,
                              final TextureResourceLoader.LazyTextureMetadata metadata,
                              final long now,
                              final boolean evictable) {
        MANAGED_TEXTURES.put(texture,
                ManagedTextureEntry.resident(resourcePath, sourceHash, metadata, now, evictable));
    }

    static void trackResident(final com.fs.graphics.TextureObject texture,
                              final String resourcePath,
                              final String sourceHash,
                              final TextureResourceLoader.LazyTextureMetadata metadata,
                              final long now,
                              final boolean evictable,
                              final TextureCompressionSupport.Format compressedFormat) {
        final ManagedTextureEntry entry = ManagedTextureEntry.resident(
                resourcePath, sourceHash, metadata, now, evictable);
        entry.applyCompressedFormat(compressedFormat);
        MANAGED_TEXTURES.put(texture, entry);
    }

    static void trackPending(final com.fs.graphics.TextureObject texture,
                             final String resourcePath,
                             final String sourceHash,
                             final TextureResourceLoader.LazyTextureMetadata metadata,
                             final long now,
                             final boolean evictable) {
        MANAGED_TEXTURES.put(texture,
                ManagedTextureEntry.pending(resourcePath, sourceHash, metadata, now, evictable));
    }

    static void markResident(final com.fs.graphics.TextureObject texture, final long now) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry != null) {
            entry.markResident(now);
        }
    }

    static String managedResourcePath(final com.fs.graphics.TextureObject texture) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        return entry == null ? null : entry.resourcePath;
    }

    static String traceState(final com.fs.graphics.TextureObject texture) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry == null) {
            return "unmanaged";
        }
        return entry.pendingUpload() ? "pending" : "resident";
    }

    static void maybeEmitTextureDiagnostics(final long now) {
        final boolean shouldWriteReport = shouldWriteCompositionReport(now);
        final boolean shouldLogSummary = shouldLogManagementSummary(now);
        if (!shouldWriteReport && !shouldLogSummary) {
            return;
        }

        final List<TextureCompositionReport.TextureEntry> snapshot = snapshotTrackedTextures();
        final Instant generatedAt = Instant.now();

        if (shouldWriteReport) {
            try {
                TextureManagementReport.exportTextureCompositionReport(snapshot, TextureResidencyPolicy.configuredCompositionReportPath(), generatedAt);
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] Failed to refresh texture composition report", e);
            }
        }

        if (shouldLogSummary) {
            final long recentlyEvicted = PENDING_EVICTED_TEXTURES.get();
            final long totalEvicted = TOTAL_EVICTED_TEXTURES.get();
            if (!snapshot.isEmpty() || recentlyEvicted > 0L || totalEvicted > 0L) {
                PENDING_EVICTED_TEXTURES.getAndSet(0L);
                String summary = TextureManagementReport.formatManagementSummary(snapshot, recentlyEvicted, totalEvicted);
                final String vram = VramProbe.queryStatus();
                if (vram != null) {
                    summary = summary + ' ' + vram;
                }
                LOGGER.info(summary);
            }
        }
    }

    private static boolean shouldWriteCompositionReport(final long now) {
        final long intervalMillis = TextureResidencyPolicy.compositionReportIntervalMillis();
        if (intervalMillis <= 0L) {
            return false;
        }

        final long scheduled = nextCompositionReportNanos;
        if (now < scheduled) {
            return false;
        }
        nextCompositionReportNanos = now + intervalMillis * 1_000_000L;
        return true;
    }

    private static boolean shouldLogManagementSummary(final long now) {
        final long intervalMillis = TextureResidencyPolicy.managementLogIntervalMillis();
        if (intervalMillis <= 0L) {
            return false;
        }

        final long scheduled = nextManagementLogNanos;
        if (now < scheduled) {
            return false;
        }
        nextManagementLogNanos = now + intervalMillis * 1_000_000L;
        return true;
    }

    private static List<TextureCompositionReport.TextureEntry> snapshotTrackedTextures() {
        final long now = System.nanoTime();
        final List<TextureCompositionReport.TextureEntry> snapshots = new ArrayList<>();
        synchronized (MANAGED_TEXTURES) {
            MANAGED_TEXTURES.forEach((texture, entry) -> {
                if (texture == null || entry == null) {
                    return;
                }

                final int textureId = readTextureId(texture, -1);
                final String state;
                if (entry.pendingUpload()) {
                    state = entry.uploadedOnce() ? "evicted-awaiting-reload" : "deferred-awaiting-first-bind";
                } else {
                    state = textureId == -1 ? "evicted-awaiting-reload" : "resident";
                }

                final long lastBindAgoMillis = Math.max(0L, (now - entry.lastBindNanos()) / 1_000_000L);
                snapshots.add(new TextureCompositionReport.TextureEntry(
                        entry.resourcePath,
                        state,
                        entry.compressionFormat,
                        entry.evictable,
                        entry.bindCount(),
                        lastBindAgoMillis,
                        entry.imageWidth,
                        entry.imageHeight,
                        entry.textureWidth,
                        entry.textureHeight,
                        entry.estimatedGpuBytes,
                        textureId,
                        entry.sourceHash
                ));
            });
        }
        return snapshots;
    }

    // 访问器直接读写字段，避免 getTextureId 重入懒加载管线；测试夹具未经 Mixin 变换，经 TextureIdAccess 替换实现。
    private static volatile LazyTextureManager.TextureIdAccess textureIdAccess = new MixinTextureIdAccess();

    private static final class MixinTextureIdAccess implements LazyTextureManager.TextureIdAccess {
        @Override
        public int read(final com.fs.graphics.TextureObject texture) {
            return ((TextureObjectAccessor) texture).ssoptimizer$readTextureId();
        }

        @Override
        public void write(final com.fs.graphics.TextureObject texture, final int textureId) {
            ((TextureObjectAccessor) texture).ssoptimizer$writeTextureId(textureId);
        }
    }

    static void installTextureIdAccessForTests(final LazyTextureManager.TextureIdAccess access) {
        if (access == null) {
            throw new IllegalArgumentException("access must not be null");
        }
        textureIdAccess = access;
    }

    static void resetTextureIdAccessForTests() {
        textureIdAccess = new MixinTextureIdAccess();
    }

    static void setTextureId(final com.fs.graphics.TextureObject texture,
                             final int textureId) {
        textureIdAccess.write(texture, textureId);
    }

    static int readTextureId(final com.fs.graphics.TextureObject texture,
                             final int fallback) {
        if (texture == null) {
            return fallback;
        }
        return textureIdAccess.read(texture);
    }

    // key 与 entry.compressedUpgradeKey 必须保持同一引用，供幂等校验。
    private record PendingCompressionUpgrade(com.fs.graphics.TextureObject texture,
                                             CompressedTextureCache.Key key) {
    }

    private static final class ManagedTextureEntry {
        private final String resourcePath;
        private volatile String sourceHash;
        private final int imageWidth;
        private final int imageHeight;
        private final int textureWidth;
        private final int textureHeight;
        private volatile long estimatedGpuBytes;
        private volatile TextureCompressionSupport.Format compressionFormat = TextureCompressionSupport.Format.NONE;
        private volatile CompressedTextureCache.Key compressedUpgradeKey;
        private volatile boolean evictable;
        private volatile boolean pendingUpload;
        private volatile boolean uploadedOnce;
        private volatile long bindCount;
        private volatile long lastBindNanos;

        private ManagedTextureEntry(final String resourcePath,
                                    final String sourceHash,
                                    final TextureResourceLoader.LazyTextureMetadata metadata,
                                    final boolean pendingUpload,
                                    final boolean uploadedOnce,
                                    final boolean evictable,
                                    final long lastBindNanos) {
            this.resourcePath = resourcePath;
            this.sourceHash = sourceHash;
            this.imageWidth = metadata.imageWidth;
            this.imageHeight = metadata.imageHeight;
            this.textureWidth = metadata.textureWidth;
            this.textureHeight = metadata.textureHeight;
            this.estimatedGpuBytes = metadata.estimatedGpuBytes;
            this.evictable = evictable;
            this.pendingUpload = pendingUpload;
            this.uploadedOnce = uploadedOnce;
            this.bindCount = 0L;
            this.lastBindNanos = lastBindNanos;
        }

        static ManagedTextureEntry pending(final String resourcePath,
                                           final String sourceHash,
                                           final TextureResourceLoader.LazyTextureMetadata metadata,
                                           final long now,
                                           final boolean evictable) {
            return new ManagedTextureEntry(resourcePath, sourceHash, metadata, true, false, evictable, now);
        }

        static ManagedTextureEntry resident(final String resourcePath,
                                            final String sourceHash,
                                            final TextureResourceLoader.LazyTextureMetadata metadata,
                                            final long now,
                                            final boolean evictable) {
            return new ManagedTextureEntry(resourcePath, sourceHash, metadata, false, true, evictable, now);
        }

        boolean pendingUpload() {
            return pendingUpload;
        }

        boolean uploadedOnce() {
            return uploadedOnce;
        }

        void touch(final long now) {
            lastBindNanos = now;
            bindCount++;
        }

        long lastBindNanos() {
            return lastBindNanos;
        }

        long bindCount() {
            return bindCount;
        }

        void markResident(final long now) {
            pendingUpload = false;
            uploadedOnce = true;
            lastBindNanos = now;
        }

        void markPendingUpload() {
            pendingUpload = true;
        }

        void markNonEvictable() {
            evictable = false;
        }

        // 仅在持有 entry 锁时调用。
        void updateSourceHash(final String sourceHash) {
            this.sourceHash = sourceHash;
        }

        void applyCompressedFormat(final TextureCompressionSupport.Format format) {
            if (format == null || format == TextureCompressionSupport.Format.NONE) {
                return;
            }
            compressionFormat = format;
            estimatedGpuBytes = TextureUploader.estimateTextureGpuBytes(
                    resourcePath, imageWidth, imageHeight, textureWidth, textureHeight, format);
        }
    }
}
