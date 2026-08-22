package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor;
import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.atlas.ShipWeaponAtlas;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Keeps only texture metadata alive during startup and postpones GPU upload
 * until the texture is first bound.
 */
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
    static final         String   RESOURCE_MANAGER_CLASS_NAME                 = ResourceLoaderFileAccessProcessor.TARGET_CLASS.replace('/', '.');
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
    private static final String   FONTS_PREFIX                                = "graphics/fonts/";
    private static final String   ORIGINAL_EAGER_LOAD_METHOD_NAME             = "ssoptimizer$loadTextureEager";
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
    // Texture ids are bound to the current OpenGL context. Launcher UI and the
    // actual game can create different contexts within the same JVM, so cached
    // texture objects need lazy in-place reload when the context generation changes.
    private static final    WeakKeyMap<com.fs.graphics.TextureObject, ContextBoundTextureEntry> CONTEXT_BOUND_TEXTURES              = new WeakKeyMap<>();
    private static final    ThreadLocal<Set<com.fs.graphics.TextureObject>>              CONTEXT_RELOAD_GUARD                =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
    /**
     * 重入卫兵的全局活跃计数（任意线程持有卫兵即 >0）：{@code isContextReloadInProgress}
     * 的稳态快速路径——上下文重建是罕见事件，但卫兵检查在纹理绑定热路径上逐次触发
     * （ThreadLocal.get + Set.contains 的 profile 热点）；计数为 0 时一次 volatile
     * 读即可否决，仅在真有重建进行时走 ThreadLocal 精确判断。
     */
    private static final    AtomicInteger                                                CONTEXT_RELOAD_GUARD_ACTIVE         = new AtomicInteger();
    private static final    ThreadLocal<String>                                          CURRENT_BOUND_TEXTURE_PATH          =
            ThreadLocal.withInitial(() -> "");
    private static final    AtomicBoolean                                                COMPOSITION_REPORT_HOOK_INSTALLED   = new AtomicBoolean(false);
    private static final    AtomicLong                                                   TOTAL_EVICTED_TEXTURES              = new AtomicLong();
    private static final    AtomicLong                                                   PENDING_EVICTED_TEXTURES            = new AtomicLong();
    private static final    Object                                                       CONTEXT_GENERATION_LOCK             = new Object();
    private static final    Method                                                       EAGER_PATH_LOAD_METHOD              = resolveEagerLoadMethod();
    private static final    Method                                                       IN_PLACE_LOAD_METHOD                = resolveInPlaceLoadMethod();
    private static final    Method                                                       ORIGINAL_LAZY_MODE_METHOD           = resolveOriginalLazyModeMethod();
    private static final    Method                                                       RESOURCE_MANAGER_FACTORY_METHOD     = resolveResourceManagerFactoryMethod();
    private static final    Method                                                       RESOURCE_MANAGER_OPEN_STREAM_METHOD = resolveResourceManagerOpenStreamMethod();
    /**
     * 延迟上传回写 textureId 的反射通道：named jar 的 TextureObject 无 public
     * setter，写字段只能反射（每纹理生命周期一次，非热路径）。
     * 读路径同样必须走字段而非 public getTextureId()：该 getter 已被懒加载管线
     * 挂钩（重定向进 {@link #getTextureId} → ensureTextureReady → readTextureId），
     * 直接调 getter 会无限递归（cpu10 实测 StackOverflowError）。
     * 已知残余成本：Field.getInt 逐次访问检查（cpu9 profile 约 0.2~0.6% 全局），
     * 无 Mixin 方案是因为单测环境无 Mixin 注入且用例构造真实 TextureObject。
     */
    private static final    Field                                                        TEXTURE_ID_FIELD                    = resolveField(com.fs.graphics.TextureObject.class, "textureId");
    private static final    Field                                                        SPECIAL_MIPMAP_SET_FIELD            = resolveField(TextureLoader.class, GameMemberNames.TextureLoader.SPECIAL_MIPMAP_SET);
    private static volatile long                                                         nextSweepNanos                      = 0L;
    private static volatile long                                                         nextCompositionReportNanos          = 0L;
    private static volatile long                                                         nextManagementLogNanos              = 0L;
    private static final    AtomicLong                                                   BIND_STATS_CALLS                     = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_REAL                      = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_DEDUPED                   = new AtomicLong();
    private static final    AtomicLong                                                   BIND_STATS_ATLAS                     = new AtomicLong();
    /** 逐路径 bind 统计（诊断用，{@code -Dssoptimizer.bindstats.paths=true} 开启）：非图集 bind 的调用数按路径计数。 */
    private static final    boolean                                                      BIND_PATH_STATS                      = Boolean.getBoolean("ssoptimizer.bindstats.paths");
    private static final    java.util.concurrent.ConcurrentHashMap<String, AtomicLong>   BIND_PATH_COUNTS                     = BIND_PATH_STATS ? new java.util.concurrent.ConcurrentHashMap<>() : null;
    private static volatile long                                                         nextBindStatsLogNanos                = 0L;
    private static volatile Object                                                       lastOpenGlContextToken              = null;
    private static volatile long                                                         currentOpenGlContextGeneration      = 0L;

    private LazyTextureManager() {
    }

    public static void installCompositionReportHookIfConfigured() {
        final String configured = configuredCompositionReportPath();
        if (!COMPOSITION_REPORT_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("[SSOptimizer] Texture composition TSV export enabled: " + resolveReportPath(configured));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                final Path exported = exportTextureCompositionReport(configured);
                LOGGER.info("[SSOptimizer] Exported texture composition report to " + exported);
            } catch (Throwable t) {
                LOGGER.warn("[SSOptimizer] Failed to export texture composition report", t);
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
        // T1 地基：首次贴图加载时触发一次压缩能力探测（结果静态缓存 + info 日志，
        // 此后调用仅一次 volatile 读）；T2 压缩管线直接复用该探测结论
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

        // worker 预备管线：读源/哈希/缓存写入已在后台完成，这里直接消费元数据
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
        return markTextureLoadedInCurrentContext(texture, effectivePath);
    }

    public static void bindTexture(final com.fs.graphics.TextureObject texture,
                                   final int target) {
        BIND_STATS_CALLS.incrementAndGet();
        final ShipWeaponAtlas.Region atlasRegion = ShipWeaponAtlas.lookup(texture.getTexturePath());
        if (atlasRegion != null) {
            // 已入舰船/武器图集：直接绑定图集纹理，原始贴图不再上传
            bindTextureDeduped(target, atlasRegion.textureId());
            noteCurrentBoundTexture(texture);
            BIND_STATS_ATLAS.incrementAndGet();
            maybeLogBindStats();
            return;
        }
        if (isContextReloadInProgress(texture)) {
            bindTextureDeduped(target, Math.max(readTextureId(texture, -1), 0));
            noteCurrentBoundTexture(texture);
            noteBindPath(texture);
            maybeLogBindStats();
            return;
        }

        final long now = System.nanoTime();
        ensureTextureReady(texture, target, now, false);

        final int textureId = readTextureId(texture, -1);
        bindTextureDeduped(target, Math.max(textureId, 0));
        noteCurrentBoundTexture(texture);
        noteBindPath(texture);
        maybeLogBindStats();
        maybeSweepIdleTextures(texture, now);
        maybeEmitTextureDiagnostics(now);
    }

    /** 诊断：记录一次非图集 bind 的逐路径计数（仅在 BIND_PATH_STATS 开启时有效）。 */
    private static void noteBindPath(final com.fs.graphics.TextureObject texture) {
        if (BIND_PATH_COUNTS == null) {
            return;
        }
        final String path = texture.getTexturePath();
        BIND_PATH_COUNTS.computeIfAbsent(path == null ? "<null>" : path, k -> new AtomicLong())
                .incrementAndGet();
    }

    /**
     * 真实 GL 绑定去重：先向驱动查询当前绑定，仅当目标纹理不同才发起
     * {@code glBindTexture}。图集化后大量精灵共享同一图集页纹理，连续绘制同页
     * 内容时本方法可把真实 bind 调用压到接近零；非图集纹理的相邻重复绑定
     * （UI 字体、粒子批次等）同样受益。
     * <p>
     * 正确性依赖 {@code glGetInteger(GL_TEXTURE_BINDING_2D)} 返回驱动侧真实状态，
     * 因此游戏内任何绕过本管理器的直接 glBindTexture 调用都不会使去重失效。
     * 仅对 {@code GL_TEXTURE_2D} 目标去重，其余目标保持无条件绑定。
     * <p>
     * 渲染线程分离模式下去重整体跳过：此时 {@code glGetInteger} 经 bridge 是一次
     * 全管线 drain（还会计入 StallDetector 熔断窗口），而重复 bind 只是渲染线程
     * 上一条廉价命令——去重的收益与成本倒挂，直接录制绑定命令。
     */
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

    public static int getTextureId(final com.fs.graphics.TextureObject texture,
                                   final int target,
                                   final int currentTextureId) {
        if (texture == null) {
            return currentTextureId;
        }
        final ShipWeaponAtlas.Region atlasRegion = ShipWeaponAtlas.lookup(texture.getTexturePath());
        if (atlasRegion != null) {
            // 已入舰船/武器图集：返回图集纹理 id（合批与绑定共用同一出口）
            return atlasRegion.textureId();
        }
        if (isContextReloadInProgress(texture)) {
            return currentTextureId;
        }

        final long now = System.nanoTime();
        final int ensuredTextureId = ensureTextureReady(texture, target, now, true);
        noteTextureIdExternalized(texture, now);
        maybeSweepIdleTextures(texture, now);
        maybeEmitTextureDiagnostics(now);
        return ensuredTextureId >= 0 ? ensuredTextureId : currentTextureId;
    }

    /**
     * 纹理绑定计数日志：每 10 秒输出一次 bindTexture 调用数、真实 glBindTexture 数、
     * 去重跳过数与其中图集页命中数，用于基准对照图集的 bind 开销削减效果。
     */
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

    /** 测试用：当前受管纹理条目数（size 会先 expunge，返回存活数）。 */
    static int managedTextureCountForTests() {
        return MANAGED_TEXTURES.size();
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

    /**
     * 游戏设置中的原始延迟加载模式是否启用。
     * 该模式下贴图元数据（尺寸/uScale）在 setTexture 时不可用，
     * {@code ShipWeaponAtlas} 等依赖元数据的功能据此跳过。
     *
     * @return 启用返回 true；设置方法不可用时按未启用处理
     */
    public static boolean isOriginalLazyModeEnabled() {
        final Method method = ORIGINAL_LAZY_MODE_METHOD;
        if (method == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(method.invoke(null));
        } catch (IllegalAccessException | InvocationTargetException e) {
            return false;
        }
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
            // 重建后实际源哈希与登记键不一致（如 worker 与主线程读源路径不同）：
            // 同步 entry 键，保证后续绑定（闲置卸载后再上传）直接命中缓存。
            entry.updateSourceHash(resolved.sourceHash());
        }

        final TextureConversionCache.CachedTextureData cached = resolved.data();
        final TexturePixelConversionResult result = cached.conversionResult();
        applyMetadata(texture, LazyTextureMetadata.from(entry.resourcePath,
                cached.imageWidth(),
                cached.imageHeight(),
                cached.hasAlpha(),
                result));

        uploadConverted(texture, target, entry.resourcePath, cached);
    }

    /**
     * 解析延迟上传所需的像素数据。
     * <p>
     * 优先按登记的 {@code registeredSourceHash} 命中压缩缓存；未命中时立即重建：
     * 直接重读源字节并解码/转换为像素，同时尝试回写磁盘缓存（回写失败不阻塞本次上传）。
     * 重建结果直接返回，不再依赖第二次缓存读取——旧实现中 {@code buildMetadata} 可能走
     * 元数据短路（索引与数据文件不同步时成为空操作）、缓存写入失败被静默吞掉或重建哈希
     * 与登记键不一致，都会导致重建后再次 miss，最终以 IOException 失败并黑采样。
     *
     * @return 像素数据与实际使用的源哈希；源不可读或解码失败时返回 null（调用方走 1x1 兜底上传）
     */
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
                TextureConversionCache.probeFingerprint(resourcePath);
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

    /**
     * 延迟重建时重读源字节。缓存未命中后不再走资源索引指纹短路（索引与数据文件不同步时
     * 该路径可能空转），直接从资源流读取原始字节。
     */
    private static byte[] readRebuildSourceBytes(final String resourcePath) {
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

    /**
     * 源不可读/解码失败时上传 1x1 白色像素贴图，避免 GL 以未生成纹理 id 的不完整纹理
     * 采样产生黑块；同时输出 ERROR 日志便于排查。白色对 normal map 是合法非零扰动，
     * 对颜色贴图是可见占位，均优于黑采样。
     */
    private static void uploadFallbackPixelTexture(final com.fs.graphics.TextureObject texture,
                                                   final int target,
                                                   final ManagedTextureEntry entry) {
        LOGGER.error("[SSOptimizer] Deferred texture data unavailable for " + entry.resourcePath
                + ", uploading 1x1 white fallback texture");
        final BufferedImage white = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        white.setRGB(0, 0, 0xFFFFFFFF);
        final TexturePixelConversionResult result = TexturePixelConverter.convert(white);
        applyMetadata(texture, LazyTextureMetadata.from(entry.resourcePath, 1, 1, true, result));
        uploadConverted(texture, target, entry.resourcePath,
                new TextureConversionCache.CachedTextureData(1, 1, true, result));
    }

    /**
     * 用已完成的像素转换结果执行纯 GL 上传（gen/bind/参数/texImage2D）。
     * 调用方必须持有 OpenGL 上下文。
     */
    private static void uploadConverted(final com.fs.graphics.TextureObject texture,
                                        final int target,
                                        final String resourcePath,
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
    }

    private static boolean shouldGenerateMipmaps(final String resourcePath,
                                                 final int imageWidth,
                                                 final int imageHeight) {
        if (isFontAtlasWithoutMipmaps(resourcePath)) {
            return false;
        }
        if (imageWidth <= 1024 && imageHeight <= 1024) {
            return true;
        }
        final Field field = SPECIAL_MIPMAP_SET_FIELD;
        if (field == null) {
            return false;
        }
        try {
            final Object value = field.get(null);
            return value instanceof Set<?> set && set.contains(resourcePath);
        } catch (IllegalAccessException e) {
            return false;
        }
    }

    private static com.fs.graphics.TextureObject eagerLoad(final TextureLoader loader,
                                                           final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                                           final String loadPath,
                                                           final String requestedPath) throws IOException {
        final Method eagerLoadMethod = EAGER_PATH_LOAD_METHOD;
        if (eagerLoadMethod == null) {
            throw new IOException("Unable to resolve TextureLoader eager load method for " + requestedPath);
        }

        try {
            final com.fs.graphics.TextureObject texture = (com.fs.graphics.TextureObject) eagerLoadMethod.invoke(
                    loader,
                    loadPath
            );
            cacheTexture(textureCache, requestedPath, loadPath, texture);
            return texture;
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to eagerly load texture " + requestedPath, cause);
        } catch (IllegalAccessException e) {
            throw new IOException("Unable to invoke TextureLoader eager load for " + requestedPath, e);
        }
    }

    /**
     * 消费 worker 预备结果：defer 贴图只登记元数据；eager 贴图在此时才把像素
     * 从 Zstd 缓存解压到 DirectBuffer 并执行纯 GL 上传，不再走
     * {@code ssoptimizer$loadTextureEager} 的原版读图/转换路径。
     */
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
            return markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        final TextureConversionCache.CachedTextureData pixels = TextureConversionCache.load(prepared.sourceHash());
        if (pixels == null) {
            LOGGER.warn("[SSOptimizer] Prepared texture cache entry missing for "
                    + effectivePath + ", falling back to eager load");
            return markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, requestedPath), effectivePath);
        }

        uploadConverted(texture, TARGET_2D, effectivePath, pixels);
        cacheTexture(textureCache, requestedPath, effectivePath, texture);
        if (trackResidency) {
            MANAGED_TEXTURES.put(texture, ManagedTextureEntry.resident(effectivePath, prepared.sourceHash(), metadata, now, true));
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
        // 字体覆盖路径在内存中提供 PNG 数据，磁盘指纹对应的仍然是原版文件，
        // 因此对字体覆盖路径跳过基于磁盘指纹的缓存查找。
        final FontResourceOverride fontOverrideService = fontResourceOverride();
        final boolean fontOverride = fontOverrideService != null
                && fontOverrideService.isEnabled()
                && fontOverrideService.isOverriddenPath(fontOverrideService.normalize(normalizedPath));

        TextureConversionCache.TextureSourceFingerprint sourceFingerprint = null;
        if (!fontOverride) {
            sourceFingerprint = TextureConversionCache.probeFingerprint(originalPath);
            if (sourceFingerprint == null && !normalizedPath.equals(originalPath)) {
                sourceFingerprint = TextureConversionCache.probeFingerprint(normalizedPath);
            }
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
        // 字体覆盖资源优先：SSOptimizer 运行时生成的字体 .fnt/.png 必须先于磁盘文件
        // 被返回，否则 FileInputStream 会读取到磁盘上（可能是汉化包预制的）位图字体
        // atlas，与 SSOptimizer 生成的 .fnt 坐标不匹配，导致渲染乱码。
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

        try {
            return new FileInputStream(originalPath);
        } catch (IOException ignored) {
        }

        if (!normalizedPath.equals(originalPath)) {
            try {
                return new FileInputStream(normalizedPath);
            } catch (IOException ignored) {
            }
        }

        InputStream input = openManagedStream(originalPath);
        if (input == null && !normalizedPath.equals(originalPath)) {
            input = openManagedStream(normalizedPath);
        }
        if (input != null) {
            return input;
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
        final Method factoryMethod = RESOURCE_MANAGER_FACTORY_METHOD;
        final Method openStreamMethod = RESOURCE_MANAGER_OPEN_STREAM_METHOD;
        if (factoryMethod == null || openStreamMethod == null || resourcePath == null || resourcePath.isBlank()) {
            return null;
        }

        try {
            final Object manager = factoryMethod.invoke(null);
            if (manager == null) {
                return null;
            }
            return (InputStream) openStreamMethod.invoke(manager, resourcePath);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            return null;
        } catch (IllegalAccessException e) {
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

    private static void setTextureId(final com.fs.graphics.TextureObject texture,
                                     final int textureId) {
        final Field field = TEXTURE_ID_FIELD;
        if (field == null) {
            throw new IllegalStateException("Texture id field is unavailable for deferred upload");
        }
        try {
            field.setInt(texture, textureId);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to set deferred texture id", e);
        }
    }

    private static int readTextureId(final com.fs.graphics.TextureObject texture,
                                     final int fallback) {
        // 必须读字段而非 getTextureId()：该 getter 已被懒加载管线挂钩（见 TEXTURE_ID_FIELD 注释）
        final Field field = TEXTURE_ID_FIELD;
        if (field == null || texture == null) {
            return fallback;
        }
        try {
            return field.getInt(texture);
        } catch (IllegalAccessException e) {
            return fallback;
        }
    }

    private static boolean hasCurrentOpenGlContext() {
        try {
            return GLContext.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
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
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int captureBoundTexture(final int target) {
        final int bindingParameter = bindingParameterForTarget(target);
        if (bindingParameter == Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        if (RenderThreadMode.isEnabled()) {
            // 分离模式下查询当前绑定是一次全管线 drain；上传路径之后所有绘制都会
            // 经 bindTextureDeduped 重新绑定正确纹理（分离模式下它无条件录制 bind），
            // 捕获/恢复旧绑定没有语义价值，直接跳过（返回哨兵使 restore 空转）
            return Integer.MIN_VALUE;
        }
        try {
            return GL11.glGetInteger(bindingParameter);
        } catch (Throwable ignored) {
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
        final Method inPlaceLoadMethod = IN_PLACE_LOAD_METHOD;
        if (inPlaceLoadMethod == null) {
            throw new IOException("Unable to resolve TextureLoader in-place load method for " + resourcePath);
        }

        final IntBuffer ids = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(ids);
        setTextureId(texture, ids.get(0));

        try {
            inPlaceLoadMethod.invoke(
                    new TextureLoader(),
                    texture,
                    resourcePath,
                    target,
                    INTERNAL_FORMAT_RGBA,
                    minFilterForResourcePath(resourcePath),
                    magFilterForResourcePath(resourcePath),
                    false
            );
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to reload texture " + resourcePath + " after OpenGL context change", cause);
        } catch (IllegalAccessException e) {
            throw new IOException("Unable to invoke in-place texture reload for " + resourcePath, e);
        }
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

    private static boolean isFontAtlasWithoutMipmaps(final String resourcePath) {
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
                LOGGER.info(formatManagementSummary(snapshot, recentlyEvicted, totalEvicted));
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
                "[SSOptimizer] Texture manager summary: tracked=%d resident=%d nonResident=%d trackedMiB=%.1f residentMiB=%.1f evictableResidentMiB=%.1f recentlyEvicted=%d totalEvicted=%d topResidentGroups=%s",
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

    /** 按压缩形态折算单 mip 层字节数（none=4B/px、bc1=0.5B/px、bc3/bc7=1B/px）。 */
    private static long mipLevelBytes(final int textureWidth,
                                      final int textureHeight,
                                      final TextureCompressionSupport.Format compressionFormat) {
        return (long) Math.max(1, textureWidth) * (long) Math.max(1, textureHeight)
                * compressionFormat.bitsPerPixel() / 8L;
    }

    private static Method resolveEagerLoadMethod() {
        return resolveEagerLoadMethod(TextureLoader.class);
    }

    static Method resolveEagerLoadMethod(final Class<?> textureLoaderClass) {
        try {
            final Method method = textureLoaderClass.getDeclaredMethod(ORIGINAL_EAGER_LOAD_METHOD_NAME, String.class);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
        }

        final Method candidate = findUniqueTextureLoadCandidate(textureLoaderClass);
        if (candidate != null) {
            candidate.setAccessible(true);
            LOGGER.warn("[SSOptimizer] TextureLoader eager alias missing; resolved fallback by signature: "
                    + textureLoaderClass.getName() + '#' + candidate.getName() + "(String)");
            return candidate;
        }

        try {
            final Method fallback = textureLoaderClass.getDeclaredMethod(GameMemberNames.TextureLoader.LOAD_TEXTURE, String.class);
            fallback.setAccessible(true);
            LOGGER.warn("[SSOptimizer] Falling back to patched TextureLoader.loadTexture(String); eager alias missing");
            return fallback;
        } catch (NoSuchMethodException fallbackException) {
            throw new IllegalStateException("Unable to resolve TextureLoader eager load method", fallbackException);
        }
    }

    private static Method resolveInPlaceLoadMethod() {
        try {
            final Method method = findDeclaredMethod(
                    TextureLoader.class,
                    com.fs.graphics.TextureObject.class,
                    false,
                    com.fs.graphics.TextureObject.class,
                    String.class,
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    boolean.class
            );
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve TextureLoader in-place load method", e);
            return null;
        }
    }

    private static Method resolveOriginalLazyModeMethod() {
        try {
            final Class<?> textureManagerClass = Class.forName(
                    GameClassNames.TEXTURE_MANAGER_DOTTED,
                    false,
                    TextureLoader.class.getClassLoader());
            return resolveOriginalLazyModeMethod(textureManagerClass);
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve original lazy texture toggle", e);
            return null;
        }
    }

    static Method resolveOriginalLazyModeMethod(final Class<?> textureManagerClass) {
        try {
            final Method method = textureManagerClass.getDeclaredMethod(
                    GameMemberNames.TextureManager.IS_LAZY_LOADING_ENABLED);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
        }

        final Method candidate = findUniqueStaticBooleanNoArgMethod(textureManagerClass);
        if (candidate != null) {
            candidate.setAccessible(true);
            LOGGER.warn("[SSOptimizer] TextureManager lazy toggle name mismatch; resolved fallback by signature: "
                    + textureManagerClass.getName() + '#' + candidate.getName() + "()");
            return candidate;
        }

        LOGGER.warn("[SSOptimizer] Could not resolve original lazy texture toggle for " + textureManagerClass.getName());
        return null;
    }

    private static Method findUniqueTextureLoadCandidate(final Class<?> textureLoaderClass) {
        Method uniqueCandidate = null;
        for (Method method : textureLoaderClass.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!com.fs.graphics.TextureObject.class.equals(method.getReturnType())) {
                continue;
            }
            if (!Arrays.equals(method.getParameterTypes(), new Class<?>[]{String.class})) {
                continue;
            }
            if (GameMemberNames.TextureLoader.LOAD_TEXTURE.equals(method.getName())) {
                continue;
            }
            if (uniqueCandidate != null) {
                return null;
            }
            uniqueCandidate = method;
        }
        return uniqueCandidate;
    }

    private static Method findUniqueStaticBooleanNoArgMethod(final Class<?> owner) {
        Method uniqueCandidate = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (!boolean.class.equals(method.getReturnType())) {
                continue;
            }
            if (method.getParameterCount() != 0) {
                continue;
            }
            if (uniqueCandidate != null) {
                return null;
            }
            uniqueCandidate = method;
        }
        return uniqueCandidate;
    }

    private static Method resolveResourceManagerFactoryMethod() {
        try {
            final Class<?> resourceManagerClass = Class.forName(RESOURCE_MANAGER_CLASS_NAME, false, TextureLoader.class.getClassLoader());
            final Method method = findDeclaredMethod(resourceManagerClass, resourceManagerClass, true);
            method.setAccessible(true);
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve resource manager singleton accessor", e);
            return null;
        }
    }

    private static Method resolveResourceManagerOpenStreamMethod() {
        try {
            final Class<?> resourceManagerClass = Class.forName(RESOURCE_MANAGER_CLASS_NAME, false, TextureLoader.class.getClassLoader());
            final Method method = findDeclaredMethod(resourceManagerClass, InputStream.class, false, String.class);
            method.setAccessible(true);
            return method;
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve resource manager stream accessor", e);
            return null;
        }
    }

    private static Field resolveField(final Class<?> owner,
                                      final String name) {
        try {
            final Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            LOGGER.warn("[SSOptimizer] Deferred texture helper could not resolve field " + owner.getName() + '.' + name);
            return null;
        }
    }

    private static Method findDeclaredMethod(final Class<?> owner,
                                             final Class<?> returnType,
                                             final boolean requireStatic,
                                             final Class<?>... parameterTypes) throws NoSuchMethodException {
        for (Method candidate : owner.getDeclaredMethods()) {
            if (candidate.getReturnType() != returnType) {
                continue;
            }
            if (Modifier.isStatic(candidate.getModifiers()) != requireStatic) {
                continue;
            }
            if (!Arrays.equals(candidate.getParameterTypes(), parameterTypes)) {
                continue;
            }
            return candidate;
        }
        throw new NoSuchMethodException("No method matched signature in " + owner.getName());
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

    /**
     * 延迟上传解析结果：像素数据 + 实际生效的源哈希（重建后可能与登记键不同）。
     */
    record ResolvedDeferredTexture(TextureConversionCache.CachedTextureData data,
                                   String sourceHash) {
    }

    private record ContextBoundTextureEntry(String resourcePath,
                                            long contextGeneration) {
    }

    private static final class ManagedTextureEntry {
        private final    String  resourcePath;
        private volatile String  sourceHash;
        private final    int     imageWidth;
        private final    int     imageHeight;
        private final    int     textureWidth;
        private final    int     textureHeight;
        private final    long    estimatedGpuBytes;
        /**
         * 压缩纹理形态（诊断基线/T2 压缩管线写入真值）：本期（T1）恒
         * {@link TextureCompressionSupport.Format#NONE}——估算字节数与 TSV
         * compression 列均以它为准，压缩本体落地前报表全 none。
         */
        private volatile TextureCompressionSupport.Format compressionFormat = TextureCompressionSupport.Format.NONE;
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

        /**
         * 延迟重建后同步实际源哈希（重建键与登记键不一致时调用），
         * 使后续绑定可直接命中缓存。仅在持 entry 锁时调用。
         */
        void updateSourceHash(final String sourceHash) {
            this.sourceHash = sourceHash;
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
                            // T1 恒 NONE（压缩本体 T2 落地后由 ManagedTextureEntry 写真值）
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
                            // T1 恒 NONE（压缩本体 T2 落地后由 ManagedTextureEntry 写真值）
                            TextureCompressionSupport.Format.NONE)
            );
        }
    }

    /** 字体覆盖服务（coremod 装配期注册；未注册=无字体覆盖，loading 不直接依赖 font 模块）。 */
    private static FontResourceOverride fontResourceOverride() {
        return ServiceRegistry.getOrNull(FontResourceOverride.class);
    }
}
