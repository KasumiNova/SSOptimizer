package github.kasuminova.ssoptimizer.font;

import org.apache.log4j.Logger;

import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase-2 runtime font cache prototype.
 * <p>
 * For supported original fonts, rendering can swap the active font instance to
 * a scale-bucket specific virtual BMFont resource generated on demand. The
 * game's own font loader (`com.fs.graphics.super.D`) is reused so runtime text
 * layout continues to consume ordinary `com.fs.graphics.super.return` objects.
 * <p>
 * This path keeps base override metrics at the original size, then swaps in a
 * higher-resolution runtime font when screen scale demands it. The original
 * requested size is preserved so the engine computes a compensating runtime
 * scale (requested / nominal), keeping logical layout size stable while
 * sampling a denser atlas.
 */
public final class RuntimeScaledFontCache {
    public static final String ENABLE_PROPERTY = "ssoptimizer.font.runtimescale.enable";
    static final        String RUNTIME_PREFIX  = "ssoptimizer/runtimefonts/";

    private static final Logger                                   LOGGER                             = Logger.getLogger(RuntimeScaledFontCache.class);
    private static final Object                                   GENERATION_LOCK                    = new Object();
    private static final Map<String, byte[]>                      GENERATED_RESOURCES                = new ConcurrentHashMap<>();
    private static final Map<String, String>                      GENERATED_RESOURCE_CANONICAL_PATHS = new ConcurrentHashMap<>();
    private static final Set<String>                              LOGGED_GENERATED_RESOURCE_ALIASES  = ConcurrentHashMap.newKeySet();
    private static final Map<RuntimeFontKey, RuntimeFontMetadata> GENERATED_FONTS                    = new ConcurrentHashMap<>();
    private static final Map<String, RuntimeFontMetadata>         RUNTIME_PATH_METADATA              = new ConcurrentHashMap<>();
    private static final Map<String, Integer>                     BASE_NOMINAL_SIZES                 = new ConcurrentHashMap<>();

    private static volatile Method fontPathGetter;
    private static volatile Method fontNominalSizeGetter;
    private static volatile Method fontLookupMethod;
    private static volatile Method fontRegisterMethod;

    private RuntimeScaledFontCache() {
    }

    public static Object resolveScaledFont(final Object currentFont,
                                           final float requestedFontSize) {
        if (currentFont == null
                || !OriginalGameFontOverrides.isEnabled()
                || !isEnabled()) {
            return currentFont;
        }

        try {
            final float screenScale = currentScreenScale();
            final float normalizedRequestedFontSize = normalizeRequestedFontSize(currentFont, requestedFontSize, screenScale);
            final FontSelection selection = resolveSelection(currentFont, normalizedRequestedFontSize, screenScale);
            if (selection == null) {
                return currentFont;
            }

            if (selection.useCurrentFont()) {
                return currentFont;
            }

            if (selection.useBaseFont()) {
                return loadFont(selection.baseFontPath(), currentFont.getClass().getClassLoader());
            }

            final RuntimeFontMetadata metadata = ensureRuntimeFont(selection, currentFont.getClass().getClassLoader());
            if (metadata == null) {
                return currentFont;
            }
            return loadFont(metadata.runtimeFontPath(), currentFont.getClass().getClassLoader());
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] Failed to resolve runtime scaled font, keeping original font instance", t);
            return currentFont;
        }
    }

    public static float adjustRequestedFontSize(final Object currentFont,
                                                final float requestedFontSize) {
        if (currentFont == null || !Float.isFinite(requestedFontSize) || requestedFontSize <= 0.0f) {
            return requestedFontSize;
        }
        if (!isEnabled()) {
            return requestedFontSize;
        }

        try {
            return normalizeRequestedFontSize(currentFont, requestedFontSize, currentScreenScale());
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] Failed to normalize requested font size for managed font render", t);
            return requestedFontSize;
        }
    }

    public static InputStream openGeneratedStream(final String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        final String normalized = OriginalGameFontOverrides.normalize(resourcePath);
        final byte[] data = GENERATED_RESOURCES.get(normalized);
        final String canonicalPath = GENERATED_RESOURCE_CANONICAL_PATHS.get(normalized);
        if (data != null
                && canonicalPath != null
                && !canonicalPath.equals(normalized)
                && LOGGED_GENERATED_RESOURCE_ALIASES.add(normalized)) {
            LOGGER.info("[SSOptimizer] Runtime generated resource alias hit: request="
                    + normalized + " canonical=" + canonicalPath);
        }
        return data == null ? null : new ByteArrayInputStream(data);
    }

    static void registerGeneratedResource(final String resourcePath,
                                          final byte[] data) {
        if (resourcePath == null || resourcePath.isBlank() || data == null) {
            return;
        }
        final String canonicalPath = OriginalGameFontOverrides.normalize(resourcePath);
        for (String alias : generatedResourceAliases(resourcePath)) {
            GENERATED_RESOURCES.put(alias, data);
            GENERATED_RESOURCE_CANONICAL_PATHS.put(alias, canonicalPath);
        }
    }

    static void clearGeneratedResources() {
        GENERATED_RESOURCES.clear();
        GENERATED_RESOURCE_CANONICAL_PATHS.clear();
        LOGGED_GENERATED_RESOURCE_ALIASES.clear();
    }

    static Set<String> generatedResourceAliases(final String resourcePath) {
        final String normalized = OriginalGameFontOverrides.normalize(resourcePath);
        if (normalized.isBlank()) {
            return Set.of();
        }

        final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add(normalized);
        if (!isRuntimeFontPath(normalized)) {
            return Set.copyOf(aliases);
        }

        final int graphicsIndex = normalized.indexOf("graphics/");
        if (graphicsIndex >= 0) {
            aliases.add(normalized.substring(graphicsIndex));
        }

        final int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < normalized.length()) {
            aliases.add(normalized.substring(lastSlash + 1));
        }
        return Set.copyOf(aliases);
    }

    static boolean isRuntimeFontPath(final String resourcePath) {
        return resourcePath != null && OriginalGameFontOverrides.normalize(resourcePath).startsWith(RUNTIME_PREFIX);
    }

    static float normalizeRequestedFontSize(final String currentFontPath,
                                            final int baseNominalSize,
                                            final int currentNominalSize,
                                            final float requestedFontSize,
                                            final float screenScale) {
        if (currentFontPath == null
                || currentFontPath.isBlank()
                || currentNominalSize <= 0
                || !Float.isFinite(requestedFontSize)
                || requestedFontSize <= 0.0f) {
            return requestedFontSize;
        }

        final float normalizedScreenScale = normalizeScreenScale(screenScale);
        if (normalizedScreenScale <= 1.001f) {
            return requestedFontSize;
        }
        if (!shouldNormalizeRequestedFontSize(currentFontPath, baseNominalSize, currentNominalSize, requestedFontSize, normalizedScreenScale)) {
            return requestedFontSize;
        }
        return requestedFontSize / normalizedScreenScale;
    }

    static String buildRuntimeFontPath(final String baseFontPath,
                                       final int scaleBucketMillis) {
        final String normalized = OriginalGameFontOverrides.normalize(baseFontPath);
        final int dot = normalized.lastIndexOf('.');
        final String base = dot >= 0 ? normalized.substring(0, dot) : normalized;
        final String extension = dot >= 0 ? normalized.substring(dot) : ".fnt";
        return RUNTIME_PREFIX + base + "_s" + scaleBucketMillis + extension;
    }

    private static FontSelection resolveSelection(final Object currentFont,
                                                  final float requestedFontSize,
                                                  final float screenScale) throws ReflectiveOperationException, IOException {
        if (!Float.isFinite(requestedFontSize) || requestedFontSize <= 0.0f) {
            return null;
        }

        final String currentFontPath = fontPath(currentFont);
        if (currentFontPath == null || currentFontPath.isBlank()) {
            return null;
        }

        final RuntimeFontMetadata runtimeMetadata = RUNTIME_PATH_METADATA.get(OriginalGameFontOverrides.normalize(currentFontPath));
        final String baseFontPath = runtimeMetadata != null
                ? runtimeMetadata.baseFontPath()
                : OriginalGameFontOverrides.normalize(currentFontPath);

        final OriginalGameFontOverrides.FontOverrideSpec baseSpec = OriginalGameFontOverrides.specForPath(baseFontPath);
        if (baseSpec == null) {
            return null;
        }

        final int baseNominalSize = runtimeMetadata != null
                ? runtimeMetadata.baseNominalFontSize()
                : BASE_NOMINAL_SIZES.computeIfAbsent(baseFontPath, RuntimeScaledFontCache::readBaseNominalSizeUnchecked);
        if (baseNominalSize <= 0) {
            return null;
        }

        final int currentNominalSize = fontNominalSize(currentFont);
        final int baseScaleBucketMillis = OriginalGameFontOverrides.baseScaleBucketMillis(baseFontPath);
    final float quantizedScale = targetScaleBucket(baseScaleBucketMillis, screenScale);
    final int quantizedScaleBucketMillis = targetScaleBucketMillis(baseScaleBucketMillis, screenScale);
        final ScaleResolution resolution = resolveScaleResolution(
                baseNominalSize,
                currentNominalSize,
                baseScaleBucketMillis,
        screenScale
        );
        return new FontSelection(baseFontPath, baseSpec, baseNominalSize, quantizedScale,
                quantizedScaleBucketMillis,
                resolution == ScaleResolution.CURRENT,
                resolution == ScaleResolution.BASE);
    }

    private static RuntimeFontMetadata ensureRuntimeFont(final FontSelection selection,
                                                         final ClassLoader loader) throws ReflectiveOperationException, IOException, FontFormatException {
        final RuntimeFontKey key = new RuntimeFontKey(selection.baseFontPath(), selection.scaleBucketMillis());
        final RuntimeFontMetadata existing = GENERATED_FONTS.get(key);
        if (existing != null) {
            return existing;
        }

        synchronized (GENERATION_LOCK) {
            final RuntimeFontMetadata cached = GENERATED_FONTS.get(key);
            if (cached != null) {
                return cached;
            }

            final String runtimeFontPath = buildRuntimeFontPath(selection.baseFontPath(), selection.scaleBucketMillis());
            final Path fontDir = OriginalGameFontOverrides.currentFontDir();
            final TtfBmFontGenerator.GeneratedFontPack pack = TtfBmFontGenerator.generateScaled(
                    selection.baseSpec(),
                    fontDir,
                    runtimeFontPath,
                    selection.scaleBucket()
            );
            for (Map.Entry<String, byte[]> entry : pack.resources().entrySet()) {
                registerGeneratedResource(entry.getKey(), entry.getValue());
            }

            final RuntimeFontMetadata metadata = new RuntimeFontMetadata(
                    selection.baseFontPath(),
                    runtimeFontPath,
                    selection.baseNominalFontSize(),
                    selection.scaleBucketMillis()
            );
            GENERATED_FONTS.put(key, metadata);
            RUNTIME_PATH_METADATA.put(OriginalGameFontOverrides.normalize(runtimeFontPath), metadata);

            if (FontArtifactExporter.isEnabled()) {
                try {
                    final OriginalGameFontOverrides.FontOverrideSpec runtimeSpec = TtfBmFontGenerator.runtimeSpecForScale(
                            selection.baseSpec(),
                            runtimeFontPath,
                            selection.scaleBucket()
                    );
                    FontArtifactExporter.exportConfigured(runtimeSpec, selection.baseFontPath(), pack);
                } catch (IOException exportFailure) {
                    LOGGER.warn("[SSOptimizer] Failed to export runtime scaled font artifacts for " + runtimeFontPath, exportFailure);
                }
            }

            loadOrRegister(runtimeFontPath, loader);
            LOGGER.info("[SSOptimizer] Runtime scaled font ready: base=" + selection.baseFontPath()
                    + " bucket=" + selection.scaleBucketMillis()
                    + " path=" + runtimeFontPath);
            return metadata;
        }
    }

    private static Object loadFont(final String fontPath,
                                   final ClassLoader loader) throws ReflectiveOperationException, IOException {
        final Object loaded = loadOrRegister(fontPath, loader);
        return loaded;
    }

    private static Object loadOrRegister(final String fontPath,
                                         final ClassLoader loader) throws ReflectiveOperationException, IOException {
        final Method lookup = resolveFontLookupMethod(loader);
        final Object loaded = invokeStatic(lookup, fontPath);
        if (loaded != null) {
            return loaded;
        }

        final Method register = resolveFontRegisterMethod(loader);
        invokeStatic(register, fontPath, fontPath);
        return invokeStatic(lookup, fontPath);
    }

    private static String fontPath(final Object currentFont) throws ReflectiveOperationException {
        final Method getter = resolveFontPathGetter(currentFont.getClass());
        return (String) getter.invoke(currentFont);
    }

    @SuppressWarnings("SameParameterValue")
    private static int fontNominalSize(final Object currentFont) throws ReflectiveOperationException {
        final Method getter = resolveFontNominalSizeGetter(currentFont.getClass());
        return (Integer) getter.invoke(currentFont);
    }

    private static Method resolveFontPathGetter(final Class<?> fontClass) throws NoSuchMethodException {
        Method getter = fontPathGetter;
        if (getter != null) {
            return getter;
        }
        getter = fontClass.getDeclaredMethod("Ô00000");
        getter.setAccessible(true);
        fontPathGetter = getter;
        return getter;
    }

    private static Method resolveFontNominalSizeGetter(final Class<?> fontClass) throws NoSuchMethodException {
        Method getter = fontNominalSizeGetter;
        if (getter != null) {
            return getter;
        }
        getter = fontClass.getDeclaredMethod("class");
        getter.setAccessible(true);
        fontNominalSizeGetter = getter;
        return getter;
    }

    private static Method resolveFontLookupMethod(final ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
        Method method = fontLookupMethod;
        if (method != null) {
            return method;
        }
        final Class<?> managerClass = Class.forName("com.fs.graphics.super.D", false, loader);
        method = managerClass.getDeclaredMethod("Ò00000", String.class);
        method.setAccessible(true);
        fontLookupMethod = method;
        return method;
    }

    private static Method resolveFontRegisterMethod(final ClassLoader loader) throws ClassNotFoundException, NoSuchMethodException {
        Method method = fontRegisterMethod;
        if (method != null) {
            return method;
        }
        final Class<?> managerClass = Class.forName("com.fs.graphics.super.D", false, loader);
        method = managerClass.getDeclaredMethod("super", String.class, String.class);
        method.setAccessible(true);
        fontRegisterMethod = method;
        return method;
    }

    private static Object invokeStatic(final Method method,
                                       final Object... args) throws ReflectiveOperationException, IOException {
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof ReflectiveOperationException reflectiveOperationException) {
                throw reflectiveOperationException;
            }
            throw new IllegalStateException("Unexpected runtime font loader failure", cause);
        }
    }

    private static int readBaseNominalSizeUnchecked(final String baseFontPath) {
        try {
            return TtfBmFontGenerator.readNominalFontSize(baseFontPath);
        } catch (IOException e) {
            LOGGER.warn("[SSOptimizer] Failed to read base nominal font size for " + baseFontPath, e);
            return 0;
        }
    }

    private static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    static float effectiveBaseScale(final float requestedFontSize,
                                    final int baseNominalSize,
                                    final float screenScale) {
        if (!Float.isFinite(requestedFontSize) || requestedFontSize <= 0.0f || baseNominalSize <= 0) {
            return 1.0f;
        }
        return effectiveRequestedFontSize(requestedFontSize, screenScale) / baseNominalSize;
    }

    static float effectiveRequestedFontSize(final float requestedFontSize,
                                            final float screenScale) {
        if (!Float.isFinite(requestedFontSize) || requestedFontSize <= 0.0f) {
            return 0.0f;
        }
        final float normalizedScreenScale = normalizeScreenScale(screenScale);
        if (normalizedScreenScale <= 1.001f) {
            return requestedFontSize;
        }
        return requestedFontSize * normalizedScreenScale;
    }

    static float normalizeScreenScale(final float screenScale) {
        return EffectiveScreenScale.normalize(screenScale);
    }

    static float bucketScale(final float scale) {
        if (!Float.isFinite(scale)) {
            return 1.0f;
        }
        final float clamped = Math.max(0.5f, Math.min(4.0f, scale));
        return Math.round(clamped / 0.125f) * 0.125f;
    }

    static int bucketScaleMillis(final float scale) {
        return Math.round(bucketScale(scale) * 1000.0f);
    }

    static int scaleBucketMillis(final int currentNominalSize,
                                 final int baseNominalSize) {
        if (currentNominalSize <= 0 || baseNominalSize <= 0) {
            return 1000;
        }
        return bucketScaleMillis((float) currentNominalSize / baseNominalSize);
    }

    static float targetScaleBucket(final int baseScaleBucketMillis,
                                   final float screenScale) {
        return targetScaleBucketMillis(baseScaleBucketMillis, screenScale) / 1000.0f;
    }

    static int targetScaleBucketMillis(final int baseScaleBucketMillis,
                                       final float screenScale) {
        final int normalizedBaseScaleBucketMillis = Math.max(1000, baseScaleBucketMillis);
        final float normalizedScreenScale = normalizeScreenScale(screenScale);
        if (normalizedScreenScale <= 1.001f) {
            return normalizedBaseScaleBucketMillis;
        }
        return Math.max(normalizedBaseScaleBucketMillis, bucketScaleMillis(normalizedScreenScale));
    }

    static ScaleResolution resolveScaleResolution(final int baseNominalSize,
                                                  final int currentNominalSize,
                                                  final int baseScaleBucketMillis,
                                                  final float screenScale) {
        final int desiredScaleBucketMillis = targetScaleBucketMillis(baseScaleBucketMillis, screenScale);
        if (desiredScaleBucketMillis == scaleBucketMillis(currentNominalSize, baseNominalSize)) {
            return ScaleResolution.CURRENT;
        }
        if (desiredScaleBucketMillis == Math.max(1000, baseScaleBucketMillis)) {
            return ScaleResolution.BASE;
        }
        return ScaleResolution.RUNTIME;
    }

    static boolean shouldNormalizeRequestedFontSize(final int currentNominalSize,
                                                    final float requestedFontSize) {
        if (currentNominalSize <= 0 || !Float.isFinite(requestedFontSize) || requestedFontSize <= 0.0f) {
            return false;
        }
        return Math.abs(currentNominalSize - requestedFontSize) <= 0.75f;
    }

    static boolean shouldNormalizeRequestedFontSize(final String currentFontPath,
                                                    final int baseNominalSize,
                                                    final int currentNominalSize,
                                                    final float requestedFontSize,
                                                    final float screenScale) {
        if (currentNominalSize <= 0
                || currentFontPath == null
                || currentFontPath.isBlank()
                || !Float.isFinite(requestedFontSize)
                || requestedFontSize <= 0.0f) {
            return false;
        }

        final String normalizedPath = OriginalGameFontOverrides.normalize(currentFontPath);
        if (isRuntimeFontPath(normalizedPath)) {
            return Math.abs((currentNominalSize * screenScale) - requestedFontSize) <= 0.75f;
        }
        return false;
    }

    private static float normalizeRequestedFontSize(final Object currentFont,
                                                    final float requestedFontSize,
                                                    final float screenScale) throws ReflectiveOperationException {
        final String currentFontPath = fontPath(currentFont);
        if (currentFontPath == null || currentFontPath.isBlank()) {
            return requestedFontSize;
        }
        final int currentNominalSize = fontNominalSize(currentFont);
        final int baseNominalSize = baseNominalSize(currentFontPath, currentNominalSize);
        return normalizeRequestedFontSize(currentFontPath, baseNominalSize, currentNominalSize, requestedFontSize, screenScale);
    }

    private static int baseNominalSize(final String currentFontPath,
                                       final int currentNominalSize) {
        final String normalizedPath = OriginalGameFontOverrides.normalize(currentFontPath);
        final RuntimeFontMetadata runtimeMetadata = RUNTIME_PATH_METADATA.get(normalizedPath);
        if (runtimeMetadata != null && runtimeMetadata.baseNominalFontSize() > 0) {
            return runtimeMetadata.baseNominalFontSize();
        }
        if (OriginalGameFontOverrides.specForPath(normalizedPath) != null) {
            return BASE_NOMINAL_SIZES.computeIfAbsent(normalizedPath, RuntimeScaledFontCache::readBaseNominalSizeUnchecked);
        }
        return currentNominalSize;
    }

    private static float currentScreenScale() {
        return EffectiveScreenScale.current();
    }

    enum ScaleResolution {
        CURRENT,
        BASE,
        RUNTIME
    }

    private record RuntimeFontKey(String baseFontPath,
                                  int scaleBucketMillis) {
    }

    private record RuntimeFontMetadata(String baseFontPath,
                                       String runtimeFontPath,
                                       int baseNominalFontSize,
                                       int scaleBucketMillis) {
    }

    private record FontSelection(String baseFontPath,
                                 OriginalGameFontOverrides.FontOverrideSpec baseSpec,
                                 int baseNominalFontSize,
                                 float scaleBucket,
                                 int scaleBucketMillis,
                                 boolean useCurrentFont,
                                 boolean useBaseFont) {
    }
}