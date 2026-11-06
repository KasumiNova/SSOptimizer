package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import com.fs.graphics.TextureManager;
import com.fs.util.ResourceLoader;
import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mixin.loading.TextureObjectAccessor;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GLContext;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public final class LazyTextureManager {
    public static final  String   COMPOSITION_REPORT_FILE_PROPERTY            = "ssoptimizer.texturecomposition.reportfile";
    static final         String   DISABLE_PROPERTY                            = "ssoptimizer.disable.lazytextureupload";
    static final         String   MINIMAL_STARTUP_PROPERTY                    = "ssoptimizer.lazytextureupload.minimalstartup";
    static final         String   MIN_GPU_BYTES_PROPERTY                      = "ssoptimizer.lazytextureupload.minbytes";
    static final         String   TRACK_MIN_GPU_BYTES_PROPERTY                = "ssoptimizer.lazytextureupload.trackminbytes";
    static final         String   IDLE_UNLOAD_MILLIS_PROPERTY                 = "ssoptimizer.lazytextureupload.idleunloadmillis";
    static final         String   PREVIEW_PROTECT_MILLIS_PROPERTY             = "ssoptimizer.lazytextureupload.previewprotectmillis";
    static final         String   SWEEP_INTERVAL_MILLIS_PROPERTY              = "ssoptimizer.lazytextureupload.sweepintervalmillis";
    static final         String   COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY = "ssoptimizer.texturecomposition.reportintervalmillis";
    static final         String   MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY     = "ssoptimizer.texturemanager.logintervalmillis";
    static final         String   HOT_RELOAD_PROPERTY                         = "ssoptimizer.texcompress.hotreload";
    static final         String   DEFAULT_COMPOSITION_REPORT_FILE             = "ssoptimizer-texture-composition.tsv";
    private static final Logger   LOGGER                                      = Logger.getLogger(LazyTextureManager.class);
    private static final int      TARGET_2D                                   = 3553;
    private static final int      TEXTURE_BINDING_2D                          = 32873;
    private static final int      INTERNAL_FORMAT_RGBA                        = 6408;
    private static final int      FORMAT_RGB                                  = 6407;
    private static final int      FORMAT_RGBA                                 = 6408;
    private static final int      FILTER_LINEAR                               = 9729;
    private static final int      FILTER_LINEAR_MIPMAP_LINEAR                 = 9987;
    private static final int      GENERATE_MIPMAP                             = 33169;
    private static final int      TYPE_UNSIGNED_BYTE                          = 5121;
    private static final long     DEFAULT_MIN_GPU_BYTES                       = 1L << 20;
    private static final long     DEFAULT_TRACK_MIN_GPU_BYTES                 = 64L << 10;
    private static final long     DEFAULT_IDLE_UNLOAD_MILLIS                  = 0L;
    private static final long     DEFAULT_PREVIEW_PROTECT_MILLIS              = 300_000L;
    private static final long     DEFAULT_SWEEP_INTERVAL_MILLIS               = 1_000L;
    private static final long     DEFAULT_COMPOSITION_REPORT_INTERVAL_MILLIS  = 5_000L;
    private static final long     DEFAULT_MANAGEMENT_LOG_INTERVAL_MILLIS      = 15_000L;
    private static final boolean  DEFAULT_MINIMAL_STARTUP                     = true;
    private static final String   GRAPHICS_PREFIX                             = "graphics/";
    static final         String   TRACE_PATH_PROPERTY                         = "ssoptimizer.texture.tracepath";
    private static final String   TRACE_PATH                                  = System.getProperty(TRACE_PATH_PROPERTY, "");
    private static final Set<String> TRACE_ALL_SEEN                           = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final String   FONTS_PREFIX                                = "graphics/fonts/";
    private static final String   INSIGNIA_PREFIX                             = FONTS_PREFIX + "insignia";
    private static final String   ORBITRON_PREFIX                             = FONTS_PREFIX + "orbitron";
    private static final String   VICTOR_PREFIX                               = FONTS_PREFIX + "victor";
    private static final String[] PREVIEW_PROTECTED_PREFIXES                  = {
            "graphics/ships/",
            "graphics/stations/",
            "graphics/weapons/"
    };
    private static final String[] EAGER_PREFIXES                              = {
            "graphics/icons/",
            "graphics/ui/",
            "graphics/hud/",
            "graphics/cursors/",
            "graphics/fonts/",
            "graphics/warroom/"
    };

    private static final    WeakKeyMap<com.fs.graphics.TextureObject, ManagedTextureEntry>      MANAGED_TEXTURES                    = new WeakKeyMap<>();
    // 纹理 id 属于当前 GL 上下文；上下文代际变化后必须在原对象上延迟重建。
    private static final    WeakKeyMap<com.fs.graphics.TextureObject, ContextBoundTextureEntry> CONTEXT_BOUND_TEXTURES              = new WeakKeyMap<>();
    private static final    ThreadLocal<Set<com.fs.graphics.TextureObject>>              CONTEXT_RELOAD_GUARD                =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    // 计数为 0 时无需读取 ThreadLocal，仅在上下文重建期间执行精确判断。
    private static final    AtomicInteger                                                CONTEXT_RELOAD_GUARD_ACTIVE         = new AtomicInteger();
    private static final    ThreadLocal<String>                                          CURRENT_BOUND_TEXTURE_PATH          =
            ThreadLocal.withInitial(() -> "");
    private static final    AtomicBoolean                                                COMPOSITION_REPORT_HOOK_INSTALLED   = new AtomicBoolean(false);
    private static final    AtomicLong                                                   TOTAL_EVICTED_TEXTURES              = new AtomicLong();
    private static final    AtomicLong                                                   PENDING_EVICTED_TEXTURES            = new AtomicLong();
    private static final    Object                                                       CONTEXT_GENERATION_LOCK             = new Object();
    private static volatile long                                                         nextSweepNanos                      = 0L;
    private static volatile long                                                         nextCompositionReportNanos          = 0L;
    private static volatile long                                                         nextManagementLogNanos              = 0L;
    private static final    AtomicLong                                                   BIND_STATS_CALLS                     = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_REAL                      = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_DEDUPED                   = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_ATLAS                     = new AtomicLong();
    private static final    boolean                                                      BIND_PATH_STATS                      = Boolean.getBoolean("ssoptimizer.bindstats.paths");
    private static final    java.util.concurrent.ConcurrentHashMap<String, AtomicLong>   BIND_PATH_COUNTS                     = BIND_PATH_STATS ? new java.util.concurrent.ConcurrentHashMap<>() : null;
    private static volatile long                                                         nextBindStatsLogNanos                = 0L;
    private static volatile Object                                                       lastOpenGlContextToken              = null;
    private static volatile long                                                         currentOpenGlContextGeneration      = 0L;
    private static final AtomicLong                                                      HOT_RELOADED_TEXTURES               = new AtomicLong();
    // 升级键同时充当入队标记，重复通知不得产生重复项。
    private static final Queue<PendingCompressionUpgrade>                                PENDING_COMPRESSION_UPGRADES        = new ConcurrentLinkedQueue<>();
    private static final long                                                            HOT_RELOAD_DRAIN_INTERVAL_NANOS     = 5_000_000L;
    private static final int                                                             HOT_RELOAD_DRAIN_BUDGET             = 8;
    private static volatile long                                                         nextHotReloadDrainNanos             = 0L;

    static {
        TextureCompressionScheduler.setCompletionListener(LazyTextureManager::noteCompressedTextureAvailable);
    }

    private LazyTextureManager() {
    }

    public static void installCompositionReportHookIfConfigured() {
        final String configured = configuredCompositionReportPath();
        if (!COMPOSITION_REPORT_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("[SSOptimizer] Texture composition TSV export enabled: " + resolveReportPath(configured));

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

    public static Path exportTextureCompositionReport(final String outputPath) throws IOException {
        return exportTextureCompositionReport(snapshotTrackedTextures(), outputPath, Instant.now());
    }

    static Path exportTextureCompositionReport(final List<TextureCompositionReport.TextureEntry> entries,
                                               final String outputPath,
                                               final Instant generatedAt) throws IOException {
        final Path target = resolveReportPath(outputPath);
        final String report = TextureCompositionReport.render(entries, generatedAt);
        final Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, report,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        return target;
    }

    public static com.fs.graphics.TextureObject loadTexture(final TextureLoader loader,
                                                            final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                                            final String resourcePath) throws IOException {
        TextureCompressionSupport.preferredFormat();
        final com.fs.graphics.TextureObject cached = (com.fs.graphics.TextureObject) textureCache.get(resourcePath);
        if (cached != null) {
            ensureContextBoundTextureTracked(cached, resourcePath);
            return cached;
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        final String effectivePath = normalizedPath.isEmpty() ? resourcePath : normalizedPath;
        if (!effectivePath.equals(resourcePath)) {
            final com.fs.graphics.TextureObject normalizedCached = (com.fs.graphics.TextureObject) textureCache.get(effectivePath);
            if (normalizedCached != null) {
                textureCache.put(resourcePath, normalizedCached);
                ensureContextBoundTextureTracked(normalizedCached, effectivePath);
                return normalizedCached;
            }
        }

        if (isOriginalLazyModeEnabled()) {
            final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
            texture.setDeferredLoadingEnabled(true);
            cacheTexture(textureCache, resourcePath, effectivePath, texture);
            return markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        if (!isEnabled()) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        if (effectivePath == null || effectivePath.isEmpty()) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, resourcePath, resourcePath), resourcePath);
        }

        final TexturePreparationRegistry.Prepared prepared = TexturePreparationRegistry.await(effectivePath);
        if (prepared != null) {
            return loadPreparedTexture(loader, textureCache, resourcePath, effectivePath, prepared);
        }

        final SourceSnapshot source = readSource(effectivePath, resourcePath);
        final LazyTextureMetadata metadata = buildMetadata(effectivePath, source);
        if (metadata == null) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        final boolean defer = shouldDefer(effectivePath, source.sourceByteLength(), metadata.estimatedGpuBytes);
        final boolean trackResidency = shouldTrackResidency(effectivePath, source.sourceByteLength(), metadata.estimatedGpuBytes);
        if (!trackResidency) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        final long now = System.nanoTime();
        if (!defer) {
            final com.fs.graphics.TextureObject texture = eagerLoad(loader, textureCache, effectivePath, resourcePath);
            MANAGED_TEXTURES.put(texture, ManagedTextureEntry.resident(effectivePath, source.sourceHash, metadata, now, true));
            return markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
        applyMetadata(texture, metadata);
        cacheTexture(textureCache, resourcePath, effectivePath, texture);
        MANAGED_TEXTURES.put(texture, ManagedTextureEntry.pending(effectivePath, source.sourceHash, metadata, now, true));
        maybeScheduleDeferredPrepass(effectivePath, source.sourceHash, metadata);
        return markTextureLoadedInCurrentContext(texture, effectivePath);
    }

    public static void bindTexture(final com.fs.graphics.TextureObject texture,
                                   final int target) {
        BIND_STATS_CALLS.incrementAndGet();
        final ShipWeaponAtlas.Region atlasRegion = ShipWeaponAtlas.lookup(texture.getTexturePath());
        if (atlasRegion != null) {
            traceTextureAccess("bindTexture", texture, atlasRegion.textureId(), true);
            bindTextureDeduped(target, atlasRegion.textureId());
            noteCurrentBoundTexture(texture);
            BIND_STATS_ATLAS.incrementAndGet();
            maybeLogBindStats();
            return;
        }
        if (isContextReloadInProgress(texture)) {
            traceTextureAccess("bindTexture", texture, Math.max(readTextureId(texture, -1), 0), false);
            bindTextureDeduped(target, Math.max(readTextureId(texture, -1), 0));
            noteCurrentBoundTexture(texture);
            noteBindPath(texture);
            maybeLogBindStats();
            return;
        }

        final long now = System.nanoTime();
        ensureTextureReady(texture, target, now, false);

        final int textureId = readTextureId(texture, -1);
        traceTextureAccess("bindTexture", texture, Math.max(textureId, 0), false);
        bindTextureDeduped(target, Math.max(textureId, 0));
        noteCurrentBoundTexture(texture);
        noteBindPath(texture);
        maybeLogBindStats();
        maybeSweepIdleTextures(texture, now);
        drainCompressionUpgrades(target, Math.max(textureId, 0), now);
        maybeEmitTextureDiagnostics(now);
    }

    private static void noteBindPath(final com.fs.graphics.TextureObject texture) {
        if (BIND_PATH_COUNTS == null) {
            return;
        }
        final String path = texture.getTexturePath();
        BIND_PATH_COUNTS.computeIfAbsent(path == null ? "<null>" : path, k -> new AtomicLong())
                .incrementAndGet();
    }

    // 渲染分离下 glGetInteger 会 drain 全管线，必须直接录制 bind。
    private static void bindTextureDeduped(final int target, final int textureId) {
        if (RenderThreadMode.isEnabled()) {
            GL11.glBindTexture(target, textureId);
            BIND_STATS_REAL.incrementAndGet();
            return;
        }
        if (target == GL11.GL_TEXTURE_2D
                && GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D) == textureId) {
            BIND_STATS_DEDUPED.incrementAndGet();
            return;
        }
        GL11.glBindTexture(target, textureId);
        BIND_STATS_REAL.incrementAndGet();
    }

    public static boolean isCurrentBoundVictorPixelFontTexture() {
        return isVictorPixelFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    public static boolean isCurrentBoundManagedFontTexture() {
        return isManagedFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    // 裸 UV 调用方必须获得独立纹理 id，图集 id 只允许由 Sprite 渲染路径解析。
    public static int getTextureId(final com.fs.graphics.TextureObject texture,
                                   final int target,
                                   final int currentTextureId) {
        if (texture == null) {
            return currentTextureId;
        }
        if (isContextReloadInProgress(texture)) {
            traceTextureAccess("getTextureId", texture, currentTextureId, false);
            return currentTextureId;
        }

        final long now = System.nanoTime();
        final int ensuredTextureId = ensureTextureReady(texture, target, now, true);
        noteTextureIdExternalized(texture, now);
        maybeSweepIdleTextures(texture, now);
        maybeEmitTextureDiagnostics(now);
        final int result = ensuredTextureId >= 0 ? ensuredTextureId : currentTextureId;
        traceTextureAccess("getTextureId", texture, result, false);
        return result;
    }

    private static void traceTextureAccess(final String op, final com.fs.graphics.TextureObject texture,
                                           final int resultId, final boolean atlasHit) {
        if (TRACE_PATH.isEmpty() || texture == null) {
            return;
        }
        final String path = texture.getTexturePath();
        if ("ALL".equals(TRACE_PATH)) {
            if (!TRACE_ALL_SEEN.add(path == null ? "<null>" : path)) {
                return;
            }
        } else if (path == null || !path.contains(TRACE_PATH)) {
            return;
        }
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        final StringBuilder caller = new StringBuilder();
        if ("ALL".equals(TRACE_PATH)) {
            for (StackTraceElement frame : new Throwable().getStackTrace()) {
                final String cls = frame.getClassName();
                if (cls.startsWith("java.") || cls.startsWith("github.kasuminova.ssoptimizer")) {
                    continue;
                }
                caller.append(cls).append('.').append(frame.getMethodName()).append(" <= ");
                if (caller.length() > 240) {
                    break;
                }
            }
        }
        LOGGER.info("[SSOptimizer][textrace] " + op + " path=" + path
                + " resultId=" + resultId + " atlasHit=" + atlasHit
                + " fieldId=" + readTextureId(texture, -1)
                + " entry=" + (entry == null ? "unmanaged" : entry.pendingUpload() ? "pending" : "resident")
                + " thread=" + Thread.currentThread().getName()
                + " identity=" + System.identityHashCode(texture)
                + (caller.length() == 0 ? "" : " caller=" + caller));
    }

    private static void maybeLogBindStats() {
        final long now = System.nanoTime();
        if (now < nextBindStatsLogNanos) {
            return;
        }
        nextBindStatsLogNanos = now + 10_000_000_000L;
        LOGGER.info("[SSOptimizer] texture binds: calls=" + BIND_STATS_CALLS.get()
                + " real=" + BIND_STATS_REAL.get()
                + " deduped=" + BIND_STATS_DEDUPED.get()
                + " atlas=" + BIND_STATS_ATLAS.get());
        if (BIND_PATH_COUNTS != null) {
            final StringBuilder top = new StringBuilder("[SSOptimizer] top non-atlas bind paths:");
            BIND_PATH_COUNTS.entrySet().stream()
                    .sorted(Map.Entry.<String, AtomicLong>comparingByValue(
                            (a, b) -> Long.compare(b.get(), a.get())))
                    .limit(15)
                    .forEach(e -> top.append(' ').append(e.getKey()).append('=').append(e.getValue().get()));
            LOGGER.info(top.toString());
        }
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
        final LazyTextureMetadata metadata = new LazyTextureMetadata(
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
        MANAGED_TEXTURES.put(texture,
                ManagedTextureEntry.resident(normalizeResourcePath(resourcePath), "test-hash", metadata, System.nanoTime(), true));
    }

    static int managedTextureCountForTests() {
        return MANAGED_TEXTURES.size();
    }

    static CompressedTextureCache.Key compressedUpgradeKeyForTests(final com.fs.graphics.TextureObject texture) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        return entry == null ? null : entry.compressedUpgradeKey;
    }

    static String configuredCompositionReportPath() {
        final String configured = System.getProperty(COMPOSITION_REPORT_FILE_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_COMPOSITION_REPORT_FILE;
        }
        return configured.trim();
    }

    static boolean isEnabled() {
        return TextureConversionCache.isEnabled() && !Boolean.getBoolean(DISABLE_PROPERTY);
    }

    static boolean shouldDefer(final String resourcePath,
                               final int sourceBytes,
                               final long estimatedGpuBytes) {
        if (isMinimalStartupTexture(resourcePath, estimatedGpuBytes)) {
            return true;
        }
        if (estimatedGpuBytes < minimumGpuBytes()) {
            return false;
        }
        if (sourceBytes < 131_072
                && !resourcePath.startsWith("graphics/backgrounds/")
                && !resourcePath.startsWith("graphics/terrain/")
                && !resourcePath.startsWith("graphics/planets/")) {
            return false;
        }
        return !isAlwaysEager(resourcePath);
    }

    static boolean minimalStartupEnabled() {
        return Boolean.parseBoolean(System.getProperty(MINIMAL_STARTUP_PROPERTY,
                Boolean.toString(DEFAULT_MINIMAL_STARTUP)));
    }

    static boolean isMinimalStartupTexture(final String resourcePath,
                                           final long estimatedGpuBytes) {
        return minimalStartupEnabled()
                && isManagedGraphicsTexture(resourcePath)
                && estimatedGpuBytes >= trackMinimumGpuBytes();
    }

    static boolean shouldTrackResidency(final String resourcePath,
                                        final int sourceBytes,
                                        final long estimatedGpuBytes) {
        if (!isManagedGraphicsTexture(resourcePath)) {
            return false;
        }
        return estimatedGpuBytes >= trackMinimumGpuBytes();
    }

    static long idleUnloadMillis() {
        return Math.max(0L, Long.getLong(IDLE_UNLOAD_MILLIS_PROPERTY, DEFAULT_IDLE_UNLOAD_MILLIS));
    }

    static long effectiveIdleUnloadMillis(final String resourcePath) {
        final long baseIdleMillis = idleUnloadMillis();
        if (!isPreviewProtectedTexture(resourcePath)) {
            return baseIdleMillis;
        }

        final long protectedMillis = Math.max(0L,
                Long.getLong(PREVIEW_PROTECT_MILLIS_PROPERTY, DEFAULT_PREVIEW_PROTECT_MILLIS));
        return Math.max(baseIdleMillis, protectedMillis);
    }

    private static long minimumGpuBytes() {
        final long configured = Long.getLong(MIN_GPU_BYTES_PROPERTY, DEFAULT_MIN_GPU_BYTES);
        return Math.max(262_144L, configured);
    }

    static long trackMinimumGpuBytes() {
        final long configured = Long.getLong(TRACK_MIN_GPU_BYTES_PROPERTY, DEFAULT_TRACK_MIN_GPU_BYTES);
        return Math.max(16_384L, configured);
    }

    private static long sweepIntervalMillis() {
        final long configured = Long.getLong(SWEEP_INTERVAL_MILLIS_PROPERTY, DEFAULT_SWEEP_INTERVAL_MILLIS);
        return Math.max(250L, configured);
    }

    static long compositionReportIntervalMillis() {
        final long configured = Long.getLong(COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY,
                DEFAULT_COMPOSITION_REPORT_INTERVAL_MILLIS);
        return Math.max(0L, configured);
    }

    static long managementLogIntervalMillis() {
        final long configured = Long.getLong(MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY,
                DEFAULT_MANAGEMENT_LOG_INTERVAL_MILLIS);
        return Math.max(0L, configured);
    }

    private static boolean isAlwaysEager(final String resourcePath) {
        for (String prefix : EAGER_PREFIXES) {
            if (resourcePath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isManagedGraphicsTexture(final String resourcePath) {
        return resourcePath != null
                && !resourcePath.isEmpty()
                && resourcePath.startsWith(GRAPHICS_PREFIX)
                && !isAlwaysEager(resourcePath);
    }

    static boolean isPreviewProtectedTexture(final String resourcePath) {
        final String normalized = normalizeResourcePath(resourcePath);
        for (final String prefix : PREVIEW_PROTECTED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isOriginalLazyModeEnabled() {
        return TextureManager.isLazyLoadingEnabled();
    }

    private static void maybeSweepIdleTextures(final com.fs.graphics.TextureObject currentTexture,
                                               final long now) {
        final long idleMillis = idleUnloadMillis();
        if (idleMillis <= 0L || MANAGED_TEXTURES.isEmpty()) {
            return;
        }

        final long sweepAt = nextSweepNanos;
        if (now < sweepAt) {
            return;
        }
        nextSweepNanos = now + sweepIntervalMillis() * 1_000_000L;

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
                final long candidateIdleNanos = effectiveIdleUnloadMillis(entry.resourcePath) * 1_000_000L;
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
        final ResolvedDeferredTexture resolved = resolveDeferredTextureData(entry.resourcePath, entry.sourceHash);
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
        applyMetadata(texture, LazyTextureMetadata.from(entry.resourcePath,
                cached.imageWidth(),
                cached.imageHeight(),
                cached.hasAlpha(),
                result));

        final TextureCompressionSupport.Format usedFormat =
                uploadConverted(texture, target, entry.resourcePath, resolved.sourceHash(), cached);
        entry.applyCompressedFormat(usedFormat);
    }

    static ResolvedDeferredTexture resolveDeferredTextureData(final String resourcePath,
                                                              final String registeredSourceHash) {
        final TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(registeredSourceHash);
        if (cached != null) {
            return new ResolvedDeferredTexture(cached, registeredSourceHash);
        }

        LOGGER.warn("[SSOptimizer] Deferred texture cache miss for " + resourcePath
                + " (hash=" + registeredSourceHash + "), rebuilding by immediate decode");

        final byte[] sourceBytes = readRebuildSourceBytes(resourcePath);
        if (sourceBytes == null) {
            LOGGER.error("[SSOptimizer] Deferred texture cache miss and source unreadable: " + resourcePath);
            return null;
        }

        final BufferedImage decoded;
        try {
            decoded = FastResourceImageDecoder.decodeUntracked(sourceBytes);
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Failed to decode texture for deferred rebuild: " + resourcePath, e);
            return null;
        }
        if (decoded == null) {
            LOGGER.error("[SSOptimizer] Texture decode produced no image for deferred rebuild: " + resourcePath);
            return null;
        }

        final String rebuiltHash = TrackedResourceImage.computeSourceHash(sourceBytes);
        if (!rebuiltHash.equals(registeredSourceHash)) {
            LOGGER.warn("[SSOptimizer] Deferred texture cache key mismatch for " + resourcePath
                    + ": registered=" + registeredSourceHash + ", rebuilt=" + rebuiltHash);
        }

        final TextureConversionCache.TextureSourceFingerprint sourceFingerprint =
                probeManagedFingerprint(resourcePath, resourcePath);
        final BufferedImage tracked = TrackedResourceImage.wrap(resourcePath, rebuiltHash, decoded, sourceFingerprint);
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        return new ResolvedDeferredTexture(
                new TextureConversionCache.CachedTextureData(
                        decoded.getWidth(),
                        decoded.getHeight(),
                        decoded.getColorModel().hasAlpha(),
                        result),
                rebuiltHash);
    }

    static byte[] readRebuildSourceBytes(final String resourcePath) {
        try (InputStream input = openStream(resourcePath, resourcePath)) {
            if (input == null) {
                return null;
            }
            return input.readAllBytes();
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Failed to read texture source for deferred rebuild: " + resourcePath, e);
            return null;
        }
    }

    private static void uploadFallbackPixelTexture(final com.fs.graphics.TextureObject texture,
                                                   final int target,
                                                   final ManagedTextureEntry entry) {
        LOGGER.error("[SSOptimizer] Deferred texture data unavailable for " + entry.resourcePath
                + ", uploading 1x1 white fallback texture");
        final BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        final TexturePixelConversionResult result = TexturePixelConverter.convert(white);
        applyMetadata(texture, LazyTextureMetadata.from(entry.resourcePath, 1, 1, true, result));
        uploadConverted(texture, target, entry.resourcePath, null,
                new TextureConversionCache.CachedTextureData(1, 1, true, result));
    }

    private static TextureCompressionSupport.Format uploadConverted(final com.fs.graphics.TextureObject texture,
                                                                    final int target,
                                                                    final String resourcePath,
                                                                    final String sourceHash,
                                                                    final TextureConversionCache.CachedTextureData cached) {
        final TexturePixelConversionResult result = cached.conversionResult();
        int textureId = readTextureId(texture, -1);
        if (textureId == -1) {
            final IntBuffer ids = BufferUtils.createIntBuffer(1);
            GL11.glGenTextures(ids);
            textureId = ids.get(0);
            setTextureId(texture, textureId);
        }

        GL11.glBindTexture(target, textureId);
        final boolean generateMipmaps = shouldGenerateMipmaps(resourcePath, cached.imageWidth(), cached.imageHeight());

        final SsobcContainer compressed = TextureCompressionEligibility.resolveCompressedUpload(
                resourcePath,
                result.textureWidth(),
                result.textureHeight(),
                result.alphaKind(),
                TextureCompressionSupport.preferredFormat(),
                TextureCompressionSupport.isS3tcAvailable(),
                sourceHash,
                generateMipmaps,
                CompressedTextureCache::load);
        if (compressed != null) {
            uploadCompressedLevels(target, resourcePath, generateMipmaps, compressed);
            return compressed.format();
        }

        if (TextureCompressionSupport.isEagerMode()) {
            final SsobcContainer eagerCompressed = compressSynchronously(
                    resourcePath, sourceHash, generateMipmaps, cached, result);
            if (eagerCompressed != null) {
                uploadCompressedLevels(target, resourcePath, generateMipmaps, eagerCompressed);
                return eagerCompressed.format();
            }
        }

        if (generateMipmaps) {
            GL11.glTexParameteri(target, 10241, FILTER_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(target, 10240, magFilterForResourcePath(resourcePath));
            GL11.glTexParameteri(TARGET_2D, GENERATE_MIPMAP, 1);
        } else {
            GL11.glTexParameteri(target, 10241, minFilterForResourcePath(resourcePath));
            GL11.glTexParameteri(target, 10240, magFilterForResourcePath(resourcePath));
            GL11.glTexParameteri(target, GENERATE_MIPMAP, 0);
        }

        final int format = cached.hasAlpha() ? FORMAT_RGBA : FORMAT_RGB;
        TextureUploadHelper.glTexImage2D(target, 0, INTERNAL_FORMAT_RGBA,
                result.textureWidth(), result.textureHeight(), 0,
                format, TYPE_UNSIGNED_BYTE, result.buffer());

        maybeScheduleCompression(resourcePath, sourceHash, generateMipmaps, result);
        return TextureCompressionSupport.Format.NONE;
    }

    // bridge 在入队时按 buffer limit 深拷贝；逐级上传前必须设置窗口并保持 mip 顺序。
    private static void uploadCompressedLevels(final int target,
                                               final String resourcePath,
                                               final boolean generateMipmaps,
                                               final SsobcContainer container) {
        final List<SsobcContainer.Level> levels = container.levels();
        final boolean mipChainUploaded = generateMipmaps && levels.size() > 1;
        if (generateMipmaps && !mipChainUploaded) {
            LOGGER.warn("[SSOptimizer] 压缩纹理缺少 mip 链（" + resourcePath
                    + "，levels=" + levels.size() + "），min filter 回退 LINEAR");
        }

        GL11.glTexParameteri(target, 10241, mipChainUploaded ? FILTER_LINEAR_MIPMAP_LINEAR : minFilterForResourcePath(resourcePath));
        GL11.glTexParameteri(target, 10240, magFilterForResourcePath(resourcePath));
        GL11.glTexParameteri(TARGET_2D, GENERATE_MIPMAP, 0);

        final byte[] raw = container.raw();
        final int dataRegionOffset = container.dataRegionOffset();
        final ByteBuffer blocks = BufferUtils.createByteBuffer(raw.length - dataRegionOffset);
        blocks.put(raw, dataRegionOffset, raw.length - dataRegionOffset);
        blocks.flip();

        final int internalFormat = container.format().glInternalFormat();
        for (int level = 0; level < levels.size(); level++) {
            final SsobcContainer.Level entry = levels.get(level);
            final ByteBuffer window = blocks.duplicate();
            final int relativeOffset = entry.dataOffset() - dataRegionOffset;
            window.position(relativeOffset);
            window.limit(relativeOffset + entry.dataLength());
            GL13.glCompressedTexImage2D(target, level, internalFormat,
                    entry.width(), entry.height(), 0, window);
        }
    }

    private static SsobcContainer compressSynchronously(final String resourcePath,
                                                        final String sourceHash,
                                                        final boolean generateMipmaps,
                                                        final TextureConversionCache.CachedTextureData cached,
                                                        final TexturePixelConversionResult result) {
        if (sourceHash == null || sourceHash.isBlank()) {
            return null;
        }
        final TextureCompressionSupport.Format format = TextureCompressionEligibility.selectFormat(
                resourcePath, result.textureWidth(), result.textureHeight(),
                result.alphaKind(), TextureCompressionSupport.preferredFormat());
        if (format == TextureCompressionSupport.Format.NONE) {
            return null;
        }

        final byte[] rgbaPixels = TextureCompressionScheduler.copyAsRgba8(
                result.buffer(), result.textureWidth(), result.textureHeight());
        if (rgbaPixels == null) {
            return null;
        }
        final ByteBuffer directPixels = BufferUtils.createByteBuffer(rgbaPixels.length);
        directPixels.put(rgbaPixels);
        directPixels.flip();

        final int mipLevels = generateMipmaps
                ? SsobcContainer.fullChainLevels(result.textureWidth(), result.textureHeight())
                : 1;
        final byte[] containerBytes = NativeTextureCompressor.compress(format.nativeId(), directPixels,
                result.textureWidth(), result.textureHeight(), mipLevels,
                TextureCompressionScheduler.resolveQuality(resourcePath),
                TextureCompressionEligibility.usesPunchThroughAlpha(format, result.alphaKind()));
        if (containerBytes == null) {
            return null;
        }

        CompressedTextureCache.store(
                new CompressedTextureCache.Key(sourceHash,
                        result.textureWidth(), result.textureHeight(), generateMipmaps, format,
                        TextureCompressionScheduler.resolveQuality(resourcePath)),
                containerBytes, resourcePath, probeManagedFingerprint(resourcePath, resourcePath));
        return SsobcContainer.parse(containerBytes);
    }

    static void noteCompressedTextureAvailable(final String resourcePath,
                                               final CompressedTextureCache.Key key) {
        if (!isHotReloadEnabled() || key == null) {
            return;
        }
        final String normalized = normalizeResourcePath(resourcePath);
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
    private static void drainCompressionUpgrades(final int target,
                                                 final int expectedBinding,
                                                 final long now) {
        if (PENDING_COMPRESSION_UPGRADES.isEmpty() || now < nextHotReloadDrainNanos) {
            return;
        }
        nextHotReloadDrainNanos = now + HOT_RELOAD_DRAIN_INTERVAL_NANOS;
        if (!hasCurrentOpenGlContext()) {
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

    static boolean isHotReloadEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(HOT_RELOAD_PROPERTY, "true"));
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
        if (!hasCurrentOpenGlContext()) {
            return;
        }
        final SsobcContainer container = CompressedTextureCache.load(key);
        final int textureId = readTextureId(texture, -1);
        if (container == null || textureId == -1) {
            entry.compressedUpgradeKey = null;
            return;
        }

        final int previousBinding = restoreBinding ? captureBoundTexture(target) : Integer.MIN_VALUE;
        try {
            GL11.glBindTexture(target, textureId);
            uploadCompressedLevels(target, entry.resourcePath, key.mipmaps(), container);
            entry.applyCompressedFormat(key.format());
            entry.compressedUpgradeKey = null;
            final long reloaded = HOT_RELOADED_TEXTURES.incrementAndGet();
            if (reloaded == 1L || reloaded % 64L == 0L) {
                LOGGER.info("[SSOptimizer] 纹理热重传进度：已原地升级为压缩形态 " + reloaded + " 张");
            }
        } finally {
            if (restoreBinding) {
                restoreBoundTexture(target, previousBinding);
            }
        }
    }

    private static void maybeScheduleCompression(final String resourcePath,
                                                 final String sourceHash,
                                                 final boolean generateMipmaps,
                                                 final TexturePixelConversionResult result) {
        final TextureCompressionSupport.Format format = TextureCompressionEligibility.selectFormat(
                resourcePath,
                result.textureWidth(),
                result.textureHeight(),
                result.alphaKind(),
                TextureCompressionSupport.preferredFormat());
        if (format == TextureCompressionSupport.Format.NONE) {
            return;
        }
        TextureCompressionScheduler.submit(resourcePath, sourceHash,
                result.textureWidth(), result.textureHeight(), generateMipmaps, format,
                TextureCompressionEligibility.usesPunchThroughAlpha(format, result.alphaKind()),
                result.buffer());
    }

    private static void maybeScheduleDeferredPrepass(final String resourcePath,
                                                     final String sourceHash,
                                                     final LazyTextureMetadata metadata) {
        final TextureCompressionSupport.Format format = TextureCompressionEligibility.selectFormat(
                resourcePath,
                metadata.textureWidth,
                metadata.textureHeight,
                metadata.alphaKind,
                TextureCompressionSupport.preferredFormat());
        if (format == TextureCompressionSupport.Format.NONE) {
            return;
        }
        final boolean mipmaps = shouldGenerateMipmaps(resourcePath, metadata.imageWidth, metadata.imageHeight);
        TextureCompressionScheduler.submitDeferredPrepass(resourcePath, sourceHash,
                metadata.textureWidth, metadata.textureHeight, mipmaps, format,
                TextureCompressionEligibility.usesPunchThroughAlpha(format, metadata.alphaKind));
    }

    static boolean shouldGenerateMipmaps(final String resourcePath,
                                         final int imageWidth,
                                         final int imageHeight) {
        if (isFontAtlasWithoutMipmaps(resourcePath)) {
            return false;
        }
        if (imageWidth <= 1024 && imageHeight <= 1024) {
            return true;
        }
        return TextureLoader.specialMipmapSet.contains(resourcePath);
    }

    private static com.fs.graphics.TextureObject eagerLoad(final TextureLoader loader,
                                                           final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                                           final String loadPath,
                                                           final String requestedPath) throws IOException {
        final com.fs.graphics.TextureObject texture = loader.loadTextureData(
                null,
                loadPath,
                TARGET_2D,
                INTERNAL_FORMAT_RGBA,
                minFilterForResourcePath(loadPath),
                magFilterForResourcePath(loadPath),
                false
        );
        cacheTexture(textureCache, requestedPath, loadPath, texture);
        return texture;
    }

    private static com.fs.graphics.TextureObject loadPreparedTexture(final TextureLoader loader,
                                                                     final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                                                     final String requestedPath,
                                                                     final String effectivePath,
                                                                     final TexturePreparationRegistry.Prepared prepared) throws IOException {
        final TextureConversionCache.CachedTextureMetadata preparedMetadata = prepared.metadata();
        final LazyTextureMetadata metadata = LazyTextureMetadata.from(effectivePath, preparedMetadata);
        final int sourceBytes = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, prepared.sourceByteLength()));
        final boolean defer = shouldDefer(effectivePath, sourceBytes, metadata.estimatedGpuBytes);
        final boolean trackResidency = shouldTrackResidency(effectivePath, sourceBytes, metadata.estimatedGpuBytes);
        final long now = System.nanoTime();

        final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
        applyMetadata(texture, metadata);

        if (trackResidency && defer) {
            cacheTexture(textureCache, requestedPath, effectivePath, texture);
            MANAGED_TEXTURES.put(texture, ManagedTextureEntry.pending(effectivePath, prepared.sourceHash(), metadata, now, true));
            maybeScheduleDeferredPrepass(effectivePath, prepared.sourceHash(), metadata);
            return markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        final TextureConversionCache.CachedTextureData pixels = TextureConversionCache.load(prepared.sourceHash());
        if (pixels == null) {
            LOGGER.warn("[SSOptimizer] Prepared texture cache entry missing for "
                    + effectivePath + ", falling back to eager load");
            return markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, requestedPath), effectivePath);
        }

        final TextureCompressionSupport.Format usedFormat =
                uploadConverted(texture, TARGET_2D, effectivePath, prepared.sourceHash(), pixels);
        cacheTexture(textureCache, requestedPath, effectivePath, texture);
        if (trackResidency) {
            final ManagedTextureEntry entry =
                    ManagedTextureEntry.resident(effectivePath, prepared.sourceHash(), metadata, now, true);
            entry.applyCompressedFormat(usedFormat);
            MANAGED_TEXTURES.put(texture, entry);
        }
        return markTextureLoadedInCurrentContext(texture, effectivePath);
    }

    private static void cacheTexture(final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                     final String requestedPath,
                                     final String normalizedPath,
                                     final com.fs.graphics.TextureObject texture) {
        textureCache.put(requestedPath, texture);
        if (normalizedPath != null && !normalizedPath.isEmpty() && !normalizedPath.equals(requestedPath)) {
            textureCache.put(normalizedPath, texture);
        }
    }

    private static SourceSnapshot readSource(final String normalizedPath,
                                             final String originalPath) throws IOException {
        // 字体覆盖使用内存 PNG，不得按磁盘原版文件指纹查缓存。
        final FontResourceOverride fontOverrideService = fontResourceOverride();
        final boolean fontOverride = fontOverrideService != null
                && fontOverrideService.isEnabled()
                && fontOverrideService.isOverriddenPath(fontOverrideService.normalize(normalizedPath));

        TextureConversionCache.TextureSourceFingerprint sourceFingerprint = null;
        if (!fontOverride) {
            // 指纹必须来自合并视图解析出的权威文件，保证模组覆盖优先。
            sourceFingerprint = probeManagedFingerprint(originalPath, normalizedPath);
        }
        if (sourceFingerprint != null) {
            final TextureConversionCache.ResourceMetadataHit metadataHit =
                    TextureConversionCache.probeMetadataByResourcePath(normalizedPath, sourceFingerprint);
            if (metadataHit != null) {
                return SourceSnapshot.cached(
                        metadataHit.sourceHash(),
                        metadataHit.sourceByteLength(),
                        metadataHit.metadata(),
                        sourceFingerprint
                );
            }
        }

        try (InputStream input = openStream(originalPath, normalizedPath)) {
            if (input == null) {
                throw new IOException("Unable to locate texture resource: " + originalPath);
            }
            final byte[] sourceBytes = input.readAllBytes();
            return SourceSnapshot.loaded(
                    sourceBytes,
                    TrackedResourceImage.computeSourceHash(sourceBytes),
                    sourceFingerprint
            );
        }
    }

    private static InputStream openStream(final String originalPath,
                                          final String normalizedPath) throws IOException {
        // 内存字体覆盖必须优先于磁盘文件，确保图集与生成的 .fnt 坐标一致。
        final FontResourceOverride fontOverrideService = fontResourceOverride();
        if (fontOverrideService != null) {
            final InputStream fontOverride = fontOverrideService.openStream(originalPath);
            if (fontOverride != null) {
                return fontOverride;
            }
            if (!normalizedPath.equals(originalPath)) {
                final InputStream normalizedFontOverride = fontOverrideService.openStream(normalizedPath);
                if (normalizedFontOverride != null) {
                    return normalizedFontOverride;
                }
            }
        }

        // 合并视图保持模组目录 > CWD > res 的原版覆盖优先级。
        File managedFile = resolveManagedFile(originalPath);
        if (managedFile == null && !normalizedPath.equals(originalPath)) {
            managedFile = resolveManagedFile(normalizedPath);
        }
        if (managedFile != null) {
            return new FileInputStream(managedFile);
        }

        InputStream input = openManagedStream(originalPath);
        if (input == null && !normalizedPath.equals(originalPath)) {
            input = openManagedStream(normalizedPath);
        }
        if (input != null) {
            return input;
        }

        final File originalFile = new File(originalPath);
        if (originalFile.isFile()) {
            return new FileInputStream(originalFile);
        }

        if (!normalizedPath.equals(originalPath)) {
            final File normalizedFile = new File(normalizedPath);
            if (normalizedFile.isFile()) {
                return new FileInputStream(normalizedFile);
            }
        }

        final ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        input = contextLoader != null ? contextLoader.getResourceAsStream(originalPath) : null;
        if (input == null && contextLoader != null && !normalizedPath.equals(originalPath)) {
            input = contextLoader.getResourceAsStream(normalizedPath);
        }
        if (input == null) {
            input = TextureLoader.class.getClassLoader().getResourceAsStream(originalPath);
        }
        if (input == null && !normalizedPath.equals(originalPath)) {
            input = TextureLoader.class.getClassLoader().getResourceAsStream(normalizedPath);
        }
        return input;
    }

    private static InputStream openManagedStream(final String resourcePath) throws IOException {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        try {
            return ResourceLoader.getInstance().openStream(resourcePath);
        } catch (final RuntimeException e) {
            // 原版以 RuntimeException 表示所有资源根均未命中，缺失时沿用既有回落链。
            LOGGER.debug("[SSOptimizer] 托管资源未命中: " + resourcePath + ": " + e.getMessage());
            return null;
        }
    }

    private static int ensureTextureReady(final com.fs.graphics.TextureObject texture,
                                          final int target,
                                          final long now,
                                          final boolean restoreBinding) {
        final long contextGeneration = observeCurrentOpenGlContextGeneration();
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry == null) {
            if (requiresContextReload(texture, contextGeneration)) {
                reloadTextureForCurrentContext(texture, target, null, now, restoreBinding, contextGeneration);
            }
            return readTextureId(texture, -1);
        }

        entry.touch(now);
        synchronized (entry) {
            if (requiresContextReload(texture, contextGeneration)) {
                reloadTextureForCurrentContext(texture, target, entry, now, restoreBinding, contextGeneration);
                return readTextureId(texture, -1);
            }

            if (entry.pendingUpload()) {
                if (!hasCurrentOpenGlContext()) {
                    LOGGER.debug("[SSOptimizer] Skipped deferred texture upload without current OpenGL context for " + entry.resourcePath);
                    return readTextureId(texture, -1);
                }

                final int previousBinding = restoreBinding ? captureBoundTexture(target) : Integer.MIN_VALUE;
                try {
                    uploadDeferredTexture(texture, target, entry);
                    entry.markResident(now);
                } catch (IOException e) {
                    LOGGER.error("[SSOptimizer] Deferred texture upload failed for " + entry.resourcePath, e);
                } finally {
                    if (restoreBinding) {
                        restoreBoundTexture(target, previousBinding);
                    }
                }
            } else if (entry.compressedUpgradeKey != null) {
                maybeUpgradeToCompressed(texture, target, entry, restoreBinding);
            }
        }

        return readTextureId(texture, -1);
    }

    private static void noteTextureIdExternalized(final com.fs.graphics.TextureObject texture,
                                                  final long now) {
        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        if (entry == null) {
            return;
        }

        entry.touch(now);
        entry.markNonEvictable();
    }

    static boolean shouldTrackContextBoundTexture(final String resourcePath) {
        return !normalizeResourcePath(resourcePath).isEmpty();
    }

    static void clearContextTracking() {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            CONTEXT_BOUND_TEXTURES.clear();
        }
        CONTEXT_RELOAD_GUARD.remove();
        CONTEXT_RELOAD_GUARD_ACTIVE.set(0);
        CURRENT_BOUND_TEXTURE_PATH.remove();
        lastOpenGlContextToken = null;
        currentOpenGlContextGeneration = 0L;
    }

    static void noteTextureLoadedForContext(final com.fs.graphics.TextureObject texture,
                                            final String resourcePath,
                                            final long contextGeneration) {
        storeContextBoundTextureEntry(texture, resourcePath, contextGeneration, true);
    }

    static boolean requiresContextReload(final com.fs.graphics.TextureObject texture,
                                         final long contextGeneration) {
        if (texture == null || contextGeneration <= 0L || isContextReloadInProgress(texture)) {
            return false;
        }
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked != null && tracked.contextGeneration != contextGeneration;
        }
    }

    static long trackedContextGeneration(final com.fs.graphics.TextureObject texture) {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked == null ? 0L : tracked.contextGeneration;
        }
    }

    static <T> T withContextReloadGuard(final com.fs.graphics.TextureObject texture,
                                        final Supplier<T> action) {
        final boolean added = enterContextReloadGuard(texture);
        try {
            return action.get();
        } finally {
            exitContextReloadGuard(texture, added);
        }
    }

    private static LazyTextureMetadata buildMetadata(final String resourcePath,
                                                     final SourceSnapshot source) throws IOException {
        if (source.cachedMetadata() != null) {
            return LazyTextureMetadata.from(resourcePath, source.cachedMetadata());
        }

        final TextureConversionCache.CachedTextureMetadata cached = TextureConversionCache.loadMetadata(source.sourceHash);
        if (cached != null) {
            return LazyTextureMetadata.from(resourcePath, cached);
        }

        final BufferedImage decoded = FastResourceImageDecoder.decodeUntracked(source.sourceBytes);
        if (decoded == null) {
            return null;
        }

        final BufferedImage tracked = TrackedResourceImage.wrap(resourcePath, source.sourceHash, decoded, source.sourceFingerprint());
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        return LazyTextureMetadata.from(resourcePath,
                tracked.getWidth(),
                tracked.getHeight(),
                tracked.getColorModel().hasAlpha(),
                result);
    }

    private static void applyMetadata(final com.fs.graphics.TextureObject texture,
                                      final LazyTextureMetadata metadata) {
        texture.setImageHeight(metadata.imageHeight);
        texture.setImageWidth(metadata.imageWidth);
        texture.setTextureWidth(metadata.textureWidth);
        texture.setTextureHeight(metadata.textureHeight);
        texture.setAverageColor(metadata.averageColor);
        texture.setUpperHalfColor(metadata.upperHalfColor);
        texture.setLowerHalfColor(metadata.lowerHalfColor);
    }

    // 访问器直接读写字段，避免 getTextureId 重入懒加载管线；测试夹具未经 Mixin 变换，经 TextureIdAccess 替换实现。
    private static volatile TextureIdAccess textureIdAccess = new MixinTextureIdAccess();

    interface TextureIdAccess {
        int read(com.fs.graphics.TextureObject texture);

        void write(com.fs.graphics.TextureObject texture, int textureId);
    }

    private static final class MixinTextureIdAccess implements TextureIdAccess {
        @Override
        public int read(final com.fs.graphics.TextureObject texture) {
            return ((TextureObjectAccessor) texture).ssoptimizer$readTextureId();
        }

        @Override
        public void write(final com.fs.graphics.TextureObject texture, final int textureId) {
            ((TextureObjectAccessor) texture).ssoptimizer$writeTextureId(textureId);
        }
    }

    static void installTextureIdAccessForTests(final TextureIdAccess access) {
        if (access == null) {
            throw new IllegalArgumentException("access must not be null");
        }
        textureIdAccess = access;
    }

    static void resetTextureIdAccessForTests() {
        textureIdAccess = new MixinTextureIdAccess();
    }

    private static void setTextureId(final com.fs.graphics.TextureObject texture,
                                     final int textureId) {
        textureIdAccess.write(texture, textureId);
    }

    private static int readTextureId(final com.fs.graphics.TextureObject texture,
                                     final int fallback) {
        if (texture == null) {
            return fallback;
        }
        return textureIdAccess.read(texture);
    }

    private static boolean hasCurrentOpenGlContext() {
        return currentOpenGlContextToken() != null;
    }

    private static long observeCurrentOpenGlContextGeneration() {
        final Object currentToken = currentOpenGlContextToken();
        if (currentToken == null) {
            return currentOpenGlContextGeneration;
        }

        synchronized (CONTEXT_GENERATION_LOCK) {
            if (currentToken != lastOpenGlContextToken) {
                lastOpenGlContextToken = currentToken;
                currentOpenGlContextGeneration++;
                LOGGER.info("[SSOptimizer] Detected OpenGL context change; cached textures will reload on next bind (generation="
                        + currentOpenGlContextGeneration
                        + ", tracked=" + trackedContextBoundTextureCount() + ')');
            }
            return currentOpenGlContextGeneration;
        }
    }

    private static Object currentOpenGlContextToken() {
        try {
            return GLContext.getCapabilities();
        } catch (RuntimeException | LinkageError e) {
            LOGGER.debug("[SSOptimizer] OpenGL context is unavailable: " + e.getMessage(), e);
            return null;
        }
    }

    private static int captureBoundTexture(final int target) {
        final int bindingParameter = bindingParameterForTarget(target);
        if (bindingParameter == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (RenderThreadMode.isEnabled()) {
            // 查询绑定会 drain 全管线，直接返回哨兵使 restore 空转。
            return Integer.MIN_VALUE;
        }
        try {
            return GL11.glGetInteger(bindingParameter);
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] Failed to query the current texture binding for target " + target, e);
            return Integer.MIN_VALUE;
        }
    }

    private static void restoreBoundTexture(final int target,
                                            final int previousBinding) {
        if (previousBinding == Integer.MIN_VALUE) {
            return;
        }
        GL11.glBindTexture(target, Math.max(previousBinding, 0));
    }

    private static int bindingParameterForTarget(final int target) {
        if (target == TARGET_2D) {
            return TEXTURE_BINDING_2D;
        }
        return Integer.MIN_VALUE;
    }

    private static com.fs.graphics.TextureObject markTextureLoadedInCurrentContext(final com.fs.graphics.TextureObject texture,
                                                                                   final String resourcePath) {
        if (texture == null) {
            return null;
        }
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), true);
        return texture;
    }

    private static void ensureContextBoundTextureTracked(final com.fs.graphics.TextureObject texture,
                                                         final String resourcePath) {
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), false);
    }

    private static void storeContextBoundTextureEntry(final com.fs.graphics.TextureObject texture,
                                                      final String resourcePath,
                                                      final long contextGeneration,
                                                      final boolean replaceGeneration) {
        if (texture == null || !shouldTrackContextBoundTexture(resourcePath)) {
            return;
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry existing = CONTEXT_BOUND_TEXTURES.get(texture);
            if (existing == null) {
                CONTEXT_BOUND_TEXTURES.put(texture, new ContextBoundTextureEntry(normalizedPath, contextGeneration));
                return;
            }

            final long generation = replaceGeneration ? contextGeneration : existing.contextGeneration;
            final String path = normalizedPath.isEmpty() ? existing.resourcePath : normalizedPath;
            CONTEXT_BOUND_TEXTURES.put(texture, new ContextBoundTextureEntry(path, generation));
        }
    }

    private static int trackedContextBoundTextureCount() {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            return CONTEXT_BOUND_TEXTURES.size();
        }
    }

    private static boolean isContextReloadInProgress(final com.fs.graphics.TextureObject texture) {
        return texture != null
                && CONTEXT_RELOAD_GUARD_ACTIVE.get() > 0
                && CONTEXT_RELOAD_GUARD.get().contains(texture);
    }

    private static boolean enterContextReloadGuard(final com.fs.graphics.TextureObject texture) {
        if (texture == null) {
            return false;
        }
        final boolean added = CONTEXT_RELOAD_GUARD.get().add(texture);
        if (added) {
            CONTEXT_RELOAD_GUARD_ACTIVE.incrementAndGet();
        }
        return added;
    }

    private static void exitContextReloadGuard(final com.fs.graphics.TextureObject texture,
                                               final boolean added) {
        final Set<com.fs.graphics.TextureObject> guardedTextures = CONTEXT_RELOAD_GUARD.get();
        if (added) {
            guardedTextures.remove(texture);
            CONTEXT_RELOAD_GUARD_ACTIVE.decrementAndGet();
        }
        if (guardedTextures.isEmpty()) {
            CONTEXT_RELOAD_GUARD.remove();
        }
    }

    private static void reloadTextureForCurrentContext(final com.fs.graphics.TextureObject texture,
                                                       final int target,
                                                       final ManagedTextureEntry entry,
                                                       final long now,
                                                       final boolean restoreBinding,
                                                       final long contextGeneration) {
        if (!hasCurrentOpenGlContext()) {
            LOGGER.debug("[SSOptimizer] Skipped context reload without current OpenGL context for texture " + texture);
            return;
        }

        final ContextBoundTextureEntry tracked;
        synchronized (CONTEXT_BOUND_TEXTURES) {
            tracked = CONTEXT_BOUND_TEXTURES.get(texture);
        }
        if (tracked == null || tracked.resourcePath == null || tracked.resourcePath.isBlank()) {
            return;
        }

        final int previousBinding = restoreBinding ? captureBoundTexture(target) : Integer.MIN_VALUE;
        final boolean guarded = enterContextReloadGuard(texture);
        // 旧纹理 id 随旧 GL 上下文销毁（无需也不能再 glDeleteTextures）
        try {
            reloadTextureInPlace(texture, target, tracked.resourcePath);
            if (entry != null) {
                entry.markResident(now);
            }
            storeContextBoundTextureEntry(texture, tracked.resourcePath, contextGeneration, true);
            LOGGER.debug("[SSOptimizer] Reloaded cached texture after OpenGL context change: " + tracked.resourcePath);
        } catch (IOException e) {
            LOGGER.error("[SSOptimizer] Failed to reload cached texture after OpenGL context change: " + tracked.resourcePath, e);
        } finally {
            exitContextReloadGuard(texture, guarded);
            if (restoreBinding) {
                restoreBoundTexture(target, previousBinding);
            }
        }
    }

    private static void reloadTextureInPlace(final com.fs.graphics.TextureObject texture,
                                             final int target,
                                             final String resourcePath) throws IOException {
        final IntBuffer ids = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(ids);
        setTextureId(texture, ids.get(0));

        new TextureLoader().loadTextureData(
                texture,
                resourcePath,
                target,
                INTERNAL_FORMAT_RGBA,
                minFilterForResourcePath(resourcePath),
                magFilterForResourcePath(resourcePath),
                false
        );
    }

    private static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null) {
            return "";
        }
        String normalized = resourcePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    static int minFilterForResourcePath(final String resourcePath) {
        return FILTER_LINEAR;
    }

    static int magFilterForResourcePath(final String resourcePath) {
        return FILTER_LINEAR;
    }

    static boolean isVictorPixelFontTexture(final String resourcePath) {
        return false;
    }

    static boolean isManagedVictorFontTexture(final String resourcePath) {
        final String normalized = normalizeResourcePath(resourcePath);
        return (normalized.startsWith(VICTOR_PREFIX)
                || normalized.startsWith("ssoptimizer/runtimefonts/" + VICTOR_PREFIX))
                && normalized.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    static boolean isManagedFontTexture(final String resourcePath) {
        return isManagedVictorFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
    }

    private static void noteCurrentBoundTexture(final com.fs.graphics.TextureObject texture) {
        CURRENT_BOUND_TEXTURE_PATH.set(resolveTrackedResourcePath(texture));
    }

    private static String resolveTrackedResourcePath(final com.fs.graphics.TextureObject texture) {
        if (texture == null) {
            return "";
        }

        final ManagedTextureEntry managed = MANAGED_TEXTURES.get(texture);
        if (managed != null && managed.resourcePath != null && !managed.resourcePath.isBlank()) {
            return managed.resourcePath;
        }

        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            if (tracked != null && tracked.resourcePath != null && !tracked.resourcePath.isBlank()) {
                return tracked.resourcePath;
            }
        }

        return "";
    }

    static boolean isSharpenedUiFontTexture(final String resourcePath) {
        final String normalized = normalizeResourcePath(resourcePath).toLowerCase(Locale.ROOT);
        return normalized.endsWith(".png")
                && (normalized.startsWith(INSIGNIA_PREFIX)
                || normalized.startsWith(ORBITRON_PREFIX)
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/insignia")
                || normalized.startsWith("ssoptimizer/runtimefonts/graphics/fonts/orbitron"));
    }

    static boolean isFontAtlasWithoutMipmaps(final String resourcePath) {
        return isManagedVictorFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
    }

    private static void maybeEmitTextureDiagnostics(final long now) {
        final boolean shouldWriteReport = shouldWriteCompositionReport(now);
        final boolean shouldLogSummary = shouldLogManagementSummary(now);
        if (!shouldWriteReport && !shouldLogSummary) {
            return;
        }

        final List<TextureCompositionReport.TextureEntry> snapshot = snapshotTrackedTextures();
        final Instant generatedAt = Instant.now();

        if (shouldWriteReport) {
            try {
                exportTextureCompositionReport(snapshot, configuredCompositionReportPath(), generatedAt);
            } catch (IOException e) {
                LOGGER.warn("[SSOptimizer] Failed to refresh texture composition report", e);
            }
        }

        if (shouldLogSummary) {
            final long recentlyEvicted = PENDING_EVICTED_TEXTURES.get();
            final long totalEvicted = TOTAL_EVICTED_TEXTURES.get();
            if (!snapshot.isEmpty() || recentlyEvicted > 0L || totalEvicted > 0L) {
                PENDING_EVICTED_TEXTURES.getAndSet(0L);
                String summary = formatManagementSummary(snapshot, recentlyEvicted, totalEvicted);
                final String vram = VramProbe.queryStatus();
                if (vram != null) {
                    summary = summary + ' ' + vram;
                }
                LOGGER.info(summary);
            }
        }
    }

    private static boolean shouldWriteCompositionReport(final long now) {
        final long intervalMillis = compositionReportIntervalMillis();
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
        final long intervalMillis = managementLogIntervalMillis();
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

    static String formatManagementSummary(final List<TextureCompositionReport.TextureEntry> entries,
                                          final long recentlyEvictedTextures,
                                          final long totalEvictedTextures) {
        long trackedEstimatedGpuBytes = 0L;
        long residentEstimatedGpuBytes = 0L;
        long evictableResidentEstimatedGpuBytes = 0L;
        int residentCount = 0;
        int nonResidentCount = 0;
        final Map<String, ResidentGroupSummary> groups = new HashMap<>();

        for (TextureCompositionReport.TextureEntry entry : entries) {
            trackedEstimatedGpuBytes += entry.estimatedGpuBytes();
            if ("resident".equals(entry.state())) {
                residentCount++;
                residentEstimatedGpuBytes += entry.estimatedGpuBytes();
                if (entry.evictable()) {
                    evictableResidentEstimatedGpuBytes += entry.estimatedGpuBytes();
                }
                groups.computeIfAbsent(textureGroupKey(entry.resourcePath()), ignored -> new ResidentGroupSummary())
                      .accumulate(entry.estimatedGpuBytes());
            } else {
                nonResidentCount++;
            }
        }

        return String.format(Locale.ROOT,
                "[SSOptimizer] Texture manager summary: tracked=%d managedResident=%d nonResident=%d trackedMiB=%.1f managedResidentMiB=%.1f evictableResidentMiB=%.1f recentlyEvicted=%d totalEvicted=%d topResidentGroups=%s",
                entries.size(),
                residentCount,
                nonResidentCount,
                toMiB(trackedEstimatedGpuBytes),
                toMiB(residentEstimatedGpuBytes),
                toMiB(evictableResidentEstimatedGpuBytes),
                recentlyEvictedTextures,
                totalEvictedTextures,
                summarizeTopResidentGroups(groups));
    }

    private static Path resolveReportPath(final String configuredPath) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("Texture composition report path is blank");
        }

        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }

        final String logsDir = System.getProperty("com.fs.starfarer.settings.paths.logs", ".");
        return Path.of(logsDir).resolve(path).toAbsolutePath().normalize();
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

    private static String textureGroupKey(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "(unknown)";
        }

        final String normalized = normalizeResourcePath(resourcePath);
        final String[] segments = normalized.split("/");
        if (segments.length >= 2) {
            return segments[0] + '/' + segments[1];
        }
        return normalized;
    }

    private static String summarizeTopResidentGroups(final Map<String, ResidentGroupSummary> groups) {
        if (groups.isEmpty()) {
            return "(none)";
        }

        final List<Map.Entry<String, ResidentGroupSummary>> sorted = new ArrayList<>(groups.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, ResidentGroupSummary>>comparingLong(entry -> entry.getValue().residentEstimatedGpuBytes)
                              .reversed()
                              .thenComparing(Map.Entry::getKey));

        final StringBuilder out = new StringBuilder();
        final int limit = Math.min(3, sorted.size());
        for (int i = 0; i < limit; i++) {
            final Map.Entry<String, ResidentGroupSummary> entry = sorted.get(i);
            if (i > 0) {
                out.append(", ");
            }
            out.append(entry.getKey())
               .append('=')
               .append(String.format(Locale.ROOT, "%.1fMiB", toMiB(entry.getValue().residentEstimatedGpuBytes)));
        }
        return out.toString();
    }

    private static double toMiB(final long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    static long estimateTextureGpuBytes(final String resourcePath,
                                        final int imageWidth,
                                        final int imageHeight,
                                        final int textureWidth,
                                        final int textureHeight,
                                        final TextureCompressionSupport.Format compressionFormat) {
        long total = mipLevelBytes(textureWidth, textureHeight, compressionFormat);
        if (!shouldGenerateMipmaps(resourcePath, imageWidth, imageHeight)) {
            return total;
        }

        int width = textureWidth;
        int height = textureHeight;
        while (width > 1 || height > 1) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            total += mipLevelBytes(width, height, compressionFormat);
        }
        return total;
    }

    private static long mipLevelBytes(final int textureWidth,
                                      final int textureHeight,
                                      final TextureCompressionSupport.Format compressionFormat) {
        return (long) Math.max(1, textureWidth) * (long) Math.max(1, textureHeight)
                * compressionFormat.bitsPerPixel() / 8L;
    }

    static File resolveManagedFile(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        try {
            return ResourceLoader.getInstance().getResourceFile(resourcePath, true);
        } catch (final RuntimeException e) {
            // 原版以 RuntimeException 表示所有资源根均未命中，缺失时返回 null 沿用既有回落链。
            LOGGER.debug("[SSOptimizer] 托管资源文件未命中: " + resourcePath + ": " + e.getMessage());
            return null;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to resolve managed texture resource " + resourcePath, e);
        }
    }

    static TextureConversionCache.TextureSourceFingerprint probeManagedFingerprint(final String originalPath,
                                                                                   final String normalizedPath) {
        File resolved = resolveManagedFile(originalPath);
        if (resolved == null && !normalizedPath.equals(originalPath)) {
            resolved = resolveManagedFile(normalizedPath);
        }
        return resolved != null ? TextureConversionCache.probeFingerprint(resolved.getAbsolutePath()) : null;
    }

    private record SourceSnapshot(byte[] sourceBytes,
                                  String sourceHash,
                                  int sourceByteLength,
                                  TextureConversionCache.CachedTextureMetadata cachedMetadata,
                                  TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
        private static SourceSnapshot loaded(final byte[] sourceBytes,
                                             final String sourceHash,
                                             final TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
            return new SourceSnapshot(sourceBytes,
                    sourceHash,
                    sourceBytes == null ? 0 : sourceBytes.length,
                    null,
                    sourceFingerprint);
        }

        private static SourceSnapshot cached(final String sourceHash,
                                             final int sourceByteLength,
                                             final TextureConversionCache.CachedTextureMetadata cachedMetadata,
                                             final TextureConversionCache.TextureSourceFingerprint sourceFingerprint) {
            return new SourceSnapshot(null,
                    sourceHash,
                    sourceByteLength,
                    cachedMetadata,
                    sourceFingerprint);
        }
    }

    record ResolvedDeferredTexture(TextureConversionCache.CachedTextureData data,
                                   String sourceHash) {
    }

    private record ContextBoundTextureEntry(String resourcePath,
                                            long contextGeneration) {
    }

    // key 与 entry.compressedUpgradeKey 必须保持同一引用，供幂等校验。
    private record PendingCompressionUpgrade(com.fs.graphics.TextureObject texture,
                                             CompressedTextureCache.Key key) {
    }

    private static final class ManagedTextureEntry {
        private final    String  resourcePath;
        private volatile String  sourceHash;
        private final    int     imageWidth;
        private final    int     imageHeight;
        private final    int     textureWidth;
        private final    int     textureHeight;
        private volatile long    estimatedGpuBytes;
        private volatile TextureCompressionSupport.Format compressionFormat = TextureCompressionSupport.Format.NONE;
        private volatile CompressedTextureCache.Key  compressedUpgradeKey;
        private volatile boolean evictable;
        private volatile boolean pendingUpload;
        private volatile boolean uploadedOnce;
        private volatile long    bindCount;
        private volatile long    lastBindNanos;

        private ManagedTextureEntry(final String resourcePath,
                                    final String sourceHash,
                                    final LazyTextureMetadata metadata,
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
                                           final LazyTextureMetadata metadata,
                                           final long now,
                                           final boolean evictable) {
            return new ManagedTextureEntry(resourcePath, sourceHash, metadata, true, false, evictable, now);
        }

        static ManagedTextureEntry resident(final String resourcePath,
                                            final String sourceHash,
                                            final LazyTextureMetadata metadata,
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
            estimatedGpuBytes = estimateTextureGpuBytes(
                    resourcePath, imageWidth, imageHeight, textureWidth, textureHeight, format);
        }
    }

    private static final class ResidentGroupSummary {
        private long residentEstimatedGpuBytes;

        void accumulate(final long gpuBytes) {
            residentEstimatedGpuBytes += gpuBytes;
        }
    }

    private record LazyTextureMetadata(int imageWidth,
                                       int imageHeight,
                                       boolean hasAlpha,
                                       AlphaKind alphaKind,
                                       int textureWidth,
                                       int textureHeight,
                                       Color averageColor,
                                       Color upperHalfColor,
                                       Color lowerHalfColor,
                                       long estimatedGpuBytes) {
        private static LazyTextureMetadata from(final String resourcePath,
                                                final TextureConversionCache.CachedTextureMetadata metadata) {
            return new LazyTextureMetadata(
                    metadata.imageWidth(),
                    metadata.imageHeight(),
                    metadata.hasAlpha(),
                    metadata.alphaKind(),
                    metadata.textureWidth(),
                    metadata.textureHeight(),
                    metadata.averageColor(),
                    metadata.upperHalfColor(),
                    metadata.lowerHalfColor(),
                    estimateTextureGpuBytes(resourcePath,
                            metadata.imageWidth(),
                            metadata.imageHeight(),
                            metadata.textureWidth(),
                            metadata.textureHeight(),
                            TextureCompressionSupport.Format.NONE)
            );
        }

        private static LazyTextureMetadata from(final String resourcePath,
                                                final int imageWidth,
                                                final int imageHeight,
                                                final boolean hasAlpha,
                                                final TexturePixelConversionResult result) {
            return new LazyTextureMetadata(
                    imageWidth,
                    imageHeight,
                    hasAlpha,
                    result.alphaKind(),
                    result.textureWidth(),
                    result.textureHeight(),
                    result.averageColor(),
                    result.upperHalfColor(),
                    result.lowerHalfColor(),
                    estimateTextureGpuBytes(resourcePath,
                            imageWidth,
                            imageHeight,
                            result.textureWidth(),
                            result.textureHeight(),
                            TextureCompressionSupport.Format.NONE)
            );
        }
    }

    /** 字体覆盖服务（coremod 装配期注册；未注册=无字体覆盖，loading 不直接依赖 font 模块）。 */
    private static FontResourceOverride fontResourceOverride() {
        return ServiceRegistry.getOrNull(FontResourceOverride.class);
    }
}
