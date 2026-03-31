package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import github.kasuminova.ssoptimizer.asm.loading.ResourceLoaderFileAccessProcessor;
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
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final int      FILTER_NEAREST                              = 9728;
    private static final int      FILTER_LINEAR                               = 9729;
    private static final int      FILTER_LINEAR_MIPMAP_LINEAR                 = 9987;
    private static final int      GENERATE_MIPMAP                             = 33169;
    private static final int      TYPE_UNSIGNED_BYTE                          = 5121;
    private static final long     DEFAULT_MIN_GPU_BYTES                       = 1L << 20;
    private static final long     DEFAULT_TRACK_MIN_GPU_BYTES                 = 64L << 10;
    private static final long     DEFAULT_IDLE_UNLOAD_MILLIS                  = 60_000L;
    private static final long     DEFAULT_SWEEP_INTERVAL_MILLIS               = 1_000L;
    private static final long     DEFAULT_COMPOSITION_REPORT_INTERVAL_MILLIS  = 5_000L;
    private static final long     DEFAULT_MANAGEMENT_LOG_INTERVAL_MILLIS      = 15_000L;
    private static final boolean  DEFAULT_MINIMAL_STARTUP                     = true;
    private static final String   GRAPHICS_PREFIX                             = "graphics/";
    private static final String   FONTS_PREFIX                                = "graphics/fonts/";
    private static final String   INSIGNIA_PREFIX                             = FONTS_PREFIX + "insignia";
    private static final String   ORBITRON_PREFIX                             = FONTS_PREFIX + "orbitron";
    private static final String   VICTOR_PREFIX                               = FONTS_PREFIX + "victor";
    private static final String[] EAGER_PREFIXES                              = {
            "graphics/icons/",
            "graphics/ui/",
            "graphics/hud/",
            "graphics/cursors/",
            "graphics/fonts/",
            "graphics/warroom/"
    };

    private static final    Map<com.fs.graphics.Object, ManagedTextureEntry>      MANAGED_TEXTURES                    =
            Collections.synchronizedMap(new WeakHashMap<>());
    // Texture ids are bound to the current OpenGL context. Launcher UI and the
    // actual game can create different contexts within the same JVM, so cached
    // texture objects need lazy in-place reload when the context generation changes.
    private static final    Map<com.fs.graphics.Object, ContextBoundTextureEntry> CONTEXT_BOUND_TEXTURES              =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final    ThreadLocal<Set<com.fs.graphics.Object>>              CONTEXT_RELOAD_GUARD                =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));
        private static final    ThreadLocal<String>                                   CURRENT_BOUND_TEXTURE_PATH          =
            ThreadLocal.withInitial(() -> "");
    private static final    AtomicBoolean                                         COMPOSITION_REPORT_HOOK_INSTALLED   = new AtomicBoolean(false);
    private static final    AtomicLong                                            TOTAL_EVICTED_TEXTURES              = new AtomicLong();
    private static final    AtomicLong                                            PENDING_EVICTED_TEXTURES            = new AtomicLong();
    private static final    Object                                                CONTEXT_GENERATION_LOCK             = new Object();
    private static final    Method                                                EAGER_PATH_LOAD_METHOD              = resolveEagerLoadMethod();
    private static final    Method                                                ORIGINAL_LAZY_MODE_METHOD           = resolveOriginalLazyModeMethod();
    private static final    Method                                                RESOURCE_MANAGER_FACTORY_METHOD     = resolveResourceManagerFactoryMethod();
    private static final    Method                                                RESOURCE_MANAGER_OPEN_STREAM_METHOD = resolveResourceManagerOpenStreamMethod();
    private static final    Field                                                 TEXTURE_ID_FIELD                    = resolveField(com.fs.graphics.Object.class, "ô00000");
    private static final    Field                                                 SPECIAL_MIPMAP_SET_FIELD            = resolveField(TextureLoader.class, "null");
    private static volatile long                                                  nextSweepNanos                      = 0L;
    private static volatile long                                                  nextCompositionReportNanos          = 0L;
    private static volatile long                                                  nextManagementLogNanos              = 0L;
    private static volatile Object                                                lastOpenGlContextToken              = null;
    private static volatile long                                                  currentOpenGlContextGeneration      = 0L;

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

    public static com.fs.graphics.Object loadTexture(final TextureLoader loader,
                                                     final HashMap textureCache,
                                                     final String resourcePath) throws IOException {
        final com.fs.graphics.Object cached = (com.fs.graphics.Object) textureCache.get(resourcePath);
        if (cached != null) {
            ensureContextBoundTextureTracked(cached, resourcePath);
            return cached;
        }

        if (isOriginalLazyModeEnabled()) {
            final com.fs.graphics.Object texture = new com.fs.graphics.Object(TARGET_2D, -1, resourcePath);
            texture.o00000(true);
            textureCache.put(resourcePath, texture);
            return markTextureLoadedInCurrentContext(texture, resourcePath);
        }

        if (!isEnabled()) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, resourcePath), resourcePath);
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        if (normalizedPath.isEmpty()) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, resourcePath), resourcePath);
        }

        final SourceSnapshot source = readSource(normalizedPath, resourcePath);
        final LazyTextureMetadata metadata = buildMetadata(normalizedPath, source);
        if (metadata == null) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, resourcePath), resourcePath);
        }

        final boolean defer = shouldDefer(normalizedPath, source.sourceBytes.length, metadata.estimatedGpuBytes);
        final boolean trackResidency = shouldTrackResidency(normalizedPath, source.sourceBytes.length, metadata.estimatedGpuBytes);
        if (!trackResidency) {
            return markTextureLoadedInCurrentContext(eagerLoad(loader, textureCache, resourcePath), resourcePath);
        }

        final long now = System.nanoTime();
        if (!defer) {
            final com.fs.graphics.Object texture = eagerLoad(loader, textureCache, resourcePath);
            MANAGED_TEXTURES.put(texture, ManagedTextureEntry.resident(normalizedPath, source.sourceHash, metadata, now, true));
            return markTextureLoadedInCurrentContext(texture, resourcePath);
        }

        final com.fs.graphics.Object texture = new com.fs.graphics.Object(TARGET_2D, -1, normalizedPath);
        applyMetadata(texture, metadata);
        textureCache.put(resourcePath, texture);
        MANAGED_TEXTURES.put(texture, ManagedTextureEntry.pending(normalizedPath, source.sourceHash, metadata, now, true));
        return markTextureLoadedInCurrentContext(texture, resourcePath);
    }

    public static void bindTexture(final com.fs.graphics.Object texture,
                                   final int target) {
        if (isContextReloadInProgress(texture)) {
            GL11.glBindTexture(target, Math.max(readTextureId(texture, -1), 0));
            noteCurrentBoundTexture(texture);
            return;
        }

        final long now = System.nanoTime();
        ensureTextureReady(texture, target, now, false);

        final int textureId = readTextureId(texture, -1);
        GL11.glBindTexture(target, Math.max(textureId, 0));
        noteCurrentBoundTexture(texture);
        maybeSweepIdleTextures(texture, now);
        maybeEmitTextureDiagnostics(now);
    }

    public static boolean isCurrentBoundVictorPixelFontTexture() {
        return isVictorPixelFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    public static boolean isCurrentBoundManagedFontTexture() {
        return isManagedFontTexture(CURRENT_BOUND_TEXTURE_PATH.get());
    }

    public static int getTextureId(final com.fs.graphics.Object texture,
                                   final int target,
                                   final int currentTextureId) {
        if (texture == null) {
            return currentTextureId;
        }
        if (isContextReloadInProgress(texture)) {
            return currentTextureId;
        }

        final ManagedTextureEntry entry = MANAGED_TEXTURES.get(texture);
        final long now = System.nanoTime();
        final int ensuredTextureId = ensureTextureReady(texture, target, now, true);
        maybeSweepIdleTextures(texture, now);
        maybeEmitTextureDiagnostics(now);
        return ensuredTextureId >= 0 ? ensuredTextureId : currentTextureId;
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

    private static boolean isOriginalLazyModeEnabled() {
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

    private static void maybeSweepIdleTextures(final com.fs.graphics.Object currentTexture,
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

        final long idleNanos = idleMillis * 1_000_000L;
        int evicted = 0;
        synchronized (MANAGED_TEXTURES) {
            for (Map.Entry<com.fs.graphics.Object, ManagedTextureEntry> managedEntry : MANAGED_TEXTURES.entrySet()) {
                final com.fs.graphics.Object candidate = managedEntry.getKey();
                if (candidate == null || candidate == currentTexture) {
                    continue;
                }

                final ManagedTextureEntry entry = managedEntry.getValue();
                if (entry == null || entry.pendingUpload()) {
                    continue;
                }

                final int textureId = readTextureId(candidate, -1);
                if (textureId == -1) {
                    entry.markPendingUpload();
                    continue;
                }
                if (now - entry.lastBindNanos() < idleNanos) {
                    continue;
                }

                GL11.glDeleteTextures(textureId);
                setTextureId(candidate, -1);
                entry.markPendingUpload();
                evicted++;
            }
        }

        if (evicted > 0) {
            TOTAL_EVICTED_TEXTURES.addAndGet(evicted);
            PENDING_EVICTED_TEXTURES.addAndGet(evicted);
            LOGGER.debug("[SSOptimizer] Evicted " + evicted + " idle texture(s) from VRAM");
        }
    }

    private static void uploadDeferredTexture(final com.fs.graphics.Object texture,
                                              final int target,
                                              final ManagedTextureEntry entry) throws IOException {
        TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(entry.sourceHash);
        if (cached == null) {
            final SourceSnapshot source = readSource(entry.resourcePath, entry.resourcePath);
            buildMetadata(entry.resourcePath, source);
            cached = TextureConversionCache.load(entry.sourceHash);
            if (cached == null) {
                throw new IOException("Texture cache miss after deferred rebuild: " + entry.resourcePath);
            }
        }

        final TexturePixelConversionResult result = cached.conversionResult();
        applyMetadata(texture, LazyTextureMetadata.from(entry.resourcePath,
                cached.imageWidth(),
                cached.imageHeight(),
                cached.hasAlpha(),
                result));

        int textureId = readTextureId(texture, -1);
        if (textureId == -1) {
            final IntBuffer ids = BufferUtils.createIntBuffer(1);
            GL11.glGenTextures(ids);
            textureId = ids.get(0);
            setTextureId(texture, textureId);
        }

        GL11.glBindTexture(target, textureId);
        final boolean generateMipmaps = shouldGenerateMipmaps(entry.resourcePath, cached.imageWidth(), cached.imageHeight());
        if (generateMipmaps) {
            GL11.glTexParameteri(target, 10241, FILTER_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(target, 10240, magFilterForResourcePath(entry.resourcePath));
            GL11.glTexParameteri(TARGET_2D, GENERATE_MIPMAP, 1);
        } else {
            GL11.glTexParameteri(target, 10241, minFilterForResourcePath(entry.resourcePath));
            GL11.glTexParameteri(target, 10240, magFilterForResourcePath(entry.resourcePath));
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

    private static com.fs.graphics.Object eagerLoad(final TextureLoader loader,
                                                    final HashMap textureCache,
                                                    final String resourcePath) throws IOException {
        try {
            final com.fs.graphics.Object texture = (com.fs.graphics.Object) EAGER_PATH_LOAD_METHOD.invoke(
                    loader,
                    null,
                    resourcePath,
                    TARGET_2D,
                    INTERNAL_FORMAT_RGBA,
                    minFilterForResourcePath(resourcePath),
                    magFilterForResourcePath(resourcePath),
                    false
            );
            textureCache.put(resourcePath, texture);
            return texture;
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Failed to eagerly load texture " + resourcePath, cause);
        } catch (IllegalAccessException e) {
            throw new IOException("Unable to invoke TextureLoader eager load for " + resourcePath, e);
        }
    }

    private static SourceSnapshot readSource(final String normalizedPath,
                                             final String originalPath) throws IOException {
        try (InputStream input = openStream(originalPath, normalizedPath)) {
            if (input == null) {
                throw new IOException("Unable to locate texture resource: " + originalPath);
            }
            final byte[] sourceBytes = input.readAllBytes();
            return new SourceSnapshot(sourceBytes, TrackedResourceImage.computeSourceHash(sourceBytes));
        }
    }

    private static InputStream openStream(final String originalPath,
                                          final String normalizedPath) throws IOException {
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

    private static int ensureTextureReady(final com.fs.graphics.Object texture,
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

    static boolean shouldTrackContextBoundTexture(final String resourcePath) {
        return !normalizeResourcePath(resourcePath).isEmpty();
    }

    static void clearContextTracking() {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            CONTEXT_BOUND_TEXTURES.clear();
        }
        CONTEXT_RELOAD_GUARD.remove();
        CURRENT_BOUND_TEXTURE_PATH.remove();
        lastOpenGlContextToken = null;
        currentOpenGlContextGeneration = 0L;
    }

    static void noteTextureLoadedForContext(final com.fs.graphics.Object texture,
                                            final String resourcePath,
                                            final long contextGeneration) {
        storeContextBoundTextureEntry(texture, resourcePath, contextGeneration, true);
    }

    static boolean requiresContextReload(final com.fs.graphics.Object texture,
                                         final long contextGeneration) {
        if (texture == null || contextGeneration <= 0L || isContextReloadInProgress(texture)) {
            return false;
        }
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked != null && tracked.contextGeneration != contextGeneration;
        }
    }

    static long trackedContextGeneration(final com.fs.graphics.Object texture) {
        synchronized (CONTEXT_BOUND_TEXTURES) {
            final ContextBoundTextureEntry tracked = CONTEXT_BOUND_TEXTURES.get(texture);
            return tracked == null ? 0L : tracked.contextGeneration;
        }
    }

    static <T> T withContextReloadGuard(final com.fs.graphics.Object texture,
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
        final TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(source.sourceHash);
        if (cached != null) {
            return LazyTextureMetadata.from(resourcePath,
                    cached.imageWidth(),
                    cached.imageHeight(),
                    cached.hasAlpha(),
                    cached.conversionResult());
        }

        final BufferedImage decoded = FastResourceImageDecoder.decodeUntracked(source.sourceBytes);
        if (decoded == null) {
            return null;
        }

        final BufferedImage tracked = TrackedResourceImage.wrap(resourcePath, source.sourceHash, decoded);
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        return LazyTextureMetadata.from(resourcePath,
                tracked.getWidth(),
                tracked.getHeight(),
                tracked.getColorModel().hasAlpha(),
                result);
    }

    private static void applyMetadata(final com.fs.graphics.Object texture,
                                      final LazyTextureMetadata metadata) {
        texture.Ò00000(metadata.imageWidth);
        texture.o00000(metadata.imageHeight);
        texture.Object(metadata.textureWidth);
        texture.Ô00000(metadata.textureHeight);
        texture.Object(metadata.averageColor);
        texture.o00000(metadata.upperHalfColor);
        texture.Ò00000(metadata.lowerHalfColor);
    }

    private static void setTextureId(final com.fs.graphics.Object texture,
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

    private static int readTextureId(final com.fs.graphics.Object texture,
                                     final int fallback) {
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

    private static com.fs.graphics.Object markTextureLoadedInCurrentContext(final com.fs.graphics.Object texture,
                                                                            final String resourcePath) {
        if (texture == null) {
            return null;
        }
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), true);
        return texture;
    }

    private static void ensureContextBoundTextureTracked(final com.fs.graphics.Object texture,
                                                         final String resourcePath) {
        storeContextBoundTextureEntry(texture, resourcePath, observeCurrentOpenGlContextGeneration(), false);
    }

    private static void storeContextBoundTextureEntry(final com.fs.graphics.Object texture,
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

    private static boolean isContextReloadInProgress(final com.fs.graphics.Object texture) {
        return texture != null && CONTEXT_RELOAD_GUARD.get().contains(texture);
    }

    private static boolean enterContextReloadGuard(final com.fs.graphics.Object texture) {
        return texture != null && CONTEXT_RELOAD_GUARD.get().add(texture);
    }

    private static void exitContextReloadGuard(final com.fs.graphics.Object texture,
                                               final boolean added) {
        final Set<com.fs.graphics.Object> guardedTextures = CONTEXT_RELOAD_GUARD.get();
        if (added) {
            guardedTextures.remove(texture);
        }
        if (guardedTextures.isEmpty()) {
            CONTEXT_RELOAD_GUARD.remove();
        }
    }

    private static void reloadTextureForCurrentContext(final com.fs.graphics.Object texture,
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

    private static void reloadTextureInPlace(final com.fs.graphics.Object texture,
                                             final int target,
                                             final String resourcePath) throws IOException {
        final IntBuffer ids = BufferUtils.createIntBuffer(1);
        GL11.glGenTextures(ids);
        setTextureId(texture, ids.get(0));

        try {
            EAGER_PATH_LOAD_METHOD.invoke(
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
        return resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
    }

    static int minFilterForResourcePath(final String resourcePath) {
        return isVictorPixelFontTexture(resourcePath) ? FILTER_NEAREST : FILTER_LINEAR;
    }

    static int magFilterForResourcePath(final String resourcePath) {
        return isVictorPixelFontTexture(resourcePath) ? FILTER_NEAREST : FILTER_LINEAR;
    }

    static boolean isVictorPixelFontTexture(final String resourcePath) {
        final String normalized = normalizeResourcePath(resourcePath);
        return (normalized.startsWith(VICTOR_PREFIX)
                || normalized.startsWith("ssoptimizer/runtimefonts/" + VICTOR_PREFIX))
                && normalized.toLowerCase(Locale.ROOT).endsWith(".png");
    }

    static boolean isManagedFontTexture(final String resourcePath) {
        return isVictorPixelFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
    }

    private static void noteCurrentBoundTexture(final com.fs.graphics.Object texture) {
        CURRENT_BOUND_TEXTURE_PATH.set(resolveTrackedResourcePath(texture));
    }

    private static String resolveTrackedResourcePath(final com.fs.graphics.Object texture) {
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
        return isVictorPixelFontTexture(resourcePath) || isSharpenedUiFontTexture(resourcePath);
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
            for (Map.Entry<com.fs.graphics.Object, ManagedTextureEntry> managedEntry : MANAGED_TEXTURES.entrySet()) {
                final com.fs.graphics.Object texture = managedEntry.getKey();
                final ManagedTextureEntry entry = managedEntry.getValue();
                if (texture == null || entry == null) {
                    continue;
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
            }
        }
        return snapshots;
    }

    private static String textureGroupKey(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return "(unknown)";
        }

        final String normalized = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;
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
                                        final int textureHeight) {
        long total = mipLevelBytes(textureWidth, textureHeight);
        if (!shouldGenerateMipmaps(resourcePath, imageWidth, imageHeight)) {
            return total;
        }

        int width = textureWidth;
        int height = textureHeight;
        while (width > 1 || height > 1) {
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            total += mipLevelBytes(width, height);
        }
        return total;
    }

    private static long mipLevelBytes(final int textureWidth,
                                      final int textureHeight) {
        return (long) Math.max(1, textureWidth) * (long) Math.max(1, textureHeight) * 4L;
    }

    private static Method resolveEagerLoadMethod() {
        try {
            final Method method = TextureLoader.class.getDeclaredMethod(
                    "super",
                    com.fs.graphics.Object.class,
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
            throw new IllegalStateException("Unable to resolve TextureLoader eager load method", e);
        }
    }

    private static Method resolveOriginalLazyModeMethod() {
        try {
            final Method method = com.fs.graphics.oOoO.class.getDeclaredMethod("class");
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            LOGGER.warn("[SSOptimizer] Could not resolve original lazy texture toggle", e);
            return null;
        }
    }

    private static Method resolveResourceManagerFactoryMethod() {
        try {
            final Class<?> resourceManagerClass = Class.forName(RESOURCE_MANAGER_CLASS_NAME, false, TextureLoader.class.getClassLoader());
            final Method method = resourceManagerClass.getDeclaredMethod("Ó00000");
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
            final Method method = resourceManagerClass.getDeclaredMethod("String", String.class);
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

    private record SourceSnapshot(byte[] sourceBytes,
                                  String sourceHash) {
    }

    private record ContextBoundTextureEntry(String resourcePath,
                                            long contextGeneration) {
    }

    private static final class ManagedTextureEntry {
        private final    String  resourcePath;
        private final    String  sourceHash;
        private final    int     imageWidth;
        private final    int     imageHeight;
        private final    int     textureWidth;
        private final    int     textureHeight;
        private final    long    estimatedGpuBytes;
        private final    boolean evictable;
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
                            result.textureHeight())
            );
        }
    }
}