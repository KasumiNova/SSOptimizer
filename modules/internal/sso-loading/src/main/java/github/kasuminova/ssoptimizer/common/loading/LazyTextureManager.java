package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.function.Supplier;

public final class LazyTextureManager {
    public static final String COMPOSITION_REPORT_FILE_PROPERTY = "ssoptimizer.texturecomposition.reportfile";
    static final String DISABLE_PROPERTY = "ssoptimizer.disable.lazytextureupload";
    static final String MINIMAL_STARTUP_PROPERTY = "ssoptimizer.lazytextureupload.minimalstartup";
    static final String MIN_GPU_BYTES_PROPERTY = "ssoptimizer.lazytextureupload.minbytes";
    static final String TRACK_MIN_GPU_BYTES_PROPERTY = "ssoptimizer.lazytextureupload.trackminbytes";
    static final String IDLE_UNLOAD_MILLIS_PROPERTY = "ssoptimizer.lazytextureupload.idleunloadmillis";
    static final String PREVIEW_PROTECT_MILLIS_PROPERTY = "ssoptimizer.lazytextureupload.previewprotectmillis";
    static final String SWEEP_INTERVAL_MILLIS_PROPERTY = "ssoptimizer.lazytextureupload.sweepintervalmillis";
    static final String COMPOSITION_REPORT_INTERVAL_MILLIS_PROPERTY = "ssoptimizer.texturecomposition.reportintervalmillis";
    static final String MANAGEMENT_LOG_INTERVAL_MILLIS_PROPERTY = "ssoptimizer.texturemanager.logintervalmillis";
    static final String HOT_RELOAD_PROPERTY = "ssoptimizer.texcompress.hotreload";
    static final String DEFAULT_COMPOSITION_REPORT_FILE = "ssoptimizer-texture-composition.tsv";
    static final String TRACE_PATH_PROPERTY = "ssoptimizer.texture.tracepath";

    static {
        TextureCompressionScheduler.setCompletionListener(LazyTextureManager::noteCompressedTextureAvailable);
    }

    private LazyTextureManager() {
    }

    public static void installCompositionReportHookIfConfigured() {
        TextureResidency.installCompositionReportHookIfConfigured();
    }

    public static Path exportTextureCompositionReport(final String outputPath) throws IOException {
        return TextureResidency.exportTextureCompositionReport(outputPath);
    }

    static Path exportTextureCompositionReport(final List<TextureCompositionReport.TextureEntry> entries,
                                               final String outputPath,
                                               final Instant generatedAt) throws IOException {
        return TextureManagementReport.exportTextureCompositionReport(entries, outputPath, generatedAt);
    }

    public static com.fs.graphics.TextureObject loadTexture(final TextureLoader loader,
                                                            final HashMap<String, com.fs.graphics.TextureObject> textureCache,
                                                            final String resourcePath) throws IOException {
        return TextureResourceLoader.loadTexture(loader, textureCache, resourcePath);
    }

    public static void bindTexture(final com.fs.graphics.TextureObject texture,
                                   final int target) {
        TextureBinding.bindTexture(texture, target);
    }

    public static boolean isCurrentBoundVictorPixelFontTexture() {
        return TextureBinding.isCurrentBoundVictorPixelFontTexture();
    }

    public static boolean isCurrentBoundManagedFontTexture() {
        return TextureBinding.isCurrentBoundManagedFontTexture();
    }

    public static int getTextureId(final com.fs.graphics.TextureObject texture,
                                   final int target,
                                   final int currentTextureId) {
        return TextureBinding.getTextureId(texture, target, currentTextureId);
    }

    static boolean isTextureEvictable(final com.fs.graphics.TextureObject texture) {
        return TextureResidency.isTextureEvictable(texture);
    }

    static void trackResidentTextureForTests(final com.fs.graphics.TextureObject texture,
                                             final String resourcePath) {
        TextureResidency.trackResidentTextureForTests(texture, resourcePath);
    }

    static int managedTextureCountForTests() {
        return TextureResidency.managedTextureCountForTests();
    }

    static CompressedTextureCache.Key compressedUpgradeKeyForTests(final com.fs.graphics.TextureObject texture) {
        return TextureResidency.compressedUpgradeKeyForTests(texture);
    }

    static String configuredCompositionReportPath() {
        return TextureResidencyPolicy.configuredCompositionReportPath();
    }

    static boolean isEnabled() {
        return TextureResidencyPolicy.isEnabled();
    }

    static boolean shouldDefer(final String resourcePath,
                               final int sourceBytes,
                               final long estimatedGpuBytes) {
        return TextureResidencyPolicy.shouldDefer(resourcePath, sourceBytes, estimatedGpuBytes);
    }

    static boolean minimalStartupEnabled() {
        return TextureResidencyPolicy.minimalStartupEnabled();
    }

    static boolean isMinimalStartupTexture(final String resourcePath,
                                           final long estimatedGpuBytes) {
        return TextureResidencyPolicy.isMinimalStartupTexture(resourcePath, estimatedGpuBytes);
    }

    static boolean shouldTrackResidency(final String resourcePath,
                                        final int sourceBytes,
                                        final long estimatedGpuBytes) {
        return TextureResidencyPolicy.shouldTrackResidency(resourcePath, sourceBytes, estimatedGpuBytes);
    }

    static long idleUnloadMillis() {
        return TextureResidencyPolicy.idleUnloadMillis();
    }

    static long effectiveIdleUnloadMillis(final String resourcePath) {
        return TextureResidencyPolicy.effectiveIdleUnloadMillis(resourcePath);
    }

    static long trackMinimumGpuBytes() {
        return TextureResidencyPolicy.trackMinimumGpuBytes();
    }

    static long compositionReportIntervalMillis() {
        return TextureResidencyPolicy.compositionReportIntervalMillis();
    }

    static long managementLogIntervalMillis() {
        return TextureResidencyPolicy.managementLogIntervalMillis();
    }

    static boolean isPreviewProtectedTexture(final String resourcePath) {
        return TextureResidencyPolicy.isPreviewProtectedTexture(resourcePath);
    }

    public static boolean isOriginalLazyModeEnabled() {
        return TextureResourceLoader.isOriginalLazyModeEnabled();
    }

    static ResolvedDeferredTexture resolveDeferredTextureData(final String resourcePath,
                                                              final String registeredSourceHash) {
        return TextureResourceLoader.resolveDeferredTextureData(resourcePath, registeredSourceHash);
    }

    static byte[] readRebuildSourceBytes(final String resourcePath) {
        return TextureResourceLoader.readRebuildSourceBytes(resourcePath);
    }

    static void noteCompressedTextureAvailable(final String resourcePath,
                                               final CompressedTextureCache.Key key) {
        TextureResidency.noteCompressedTextureAvailable(resourcePath, key);
    }

    static int pendingCompressionUpgradeCountForTests() {
        return TextureResidency.pendingCompressionUpgradeCountForTests();
    }

    static boolean isHotReloadEnabled() {
        return TextureResidencyPolicy.isHotReloadEnabled();
    }

    static boolean shouldGenerateMipmaps(final String resourcePath,
                                         final int imageWidth,
                                         final int imageHeight) {
        return TextureUploader.shouldGenerateMipmaps(resourcePath, imageWidth, imageHeight);
    }

    static boolean shouldTrackContextBoundTexture(final String resourcePath) {
        return TextureContextTracker.shouldTrackContextBoundTexture(resourcePath);
    }

    static void clearContextTracking() {
        TextureContextTracker.clearContextTracking();
    }

    static void noteTextureLoadedForContext(final com.fs.graphics.TextureObject texture,
                                            final String resourcePath,
                                            final long contextGeneration) {
        TextureContextTracker.noteTextureLoadedForContext(texture, resourcePath, contextGeneration);
    }

    static boolean requiresContextReload(final com.fs.graphics.TextureObject texture,
                                         final long contextGeneration) {
        return TextureContextTracker.requiresContextReload(texture, contextGeneration);
    }

    static long trackedContextGeneration(final com.fs.graphics.TextureObject texture) {
        return TextureContextTracker.trackedContextGeneration(texture);
    }

    static <T> T withContextReloadGuard(final com.fs.graphics.TextureObject texture,
                                        final Supplier<T> action) {
        return TextureContextTracker.withContextReloadGuard(texture, action);
    }

    interface TextureIdAccess {
        int read(com.fs.graphics.TextureObject texture);

        void write(com.fs.graphics.TextureObject texture, int textureId);
    }

    static void installTextureIdAccessForTests(final TextureIdAccess access) {
        TextureResidency.installTextureIdAccessForTests(access);
    }

    static void resetTextureIdAccessForTests() {
        TextureResidency.resetTextureIdAccessForTests();
    }

    static int minFilterForResourcePath(final String resourcePath) {
        return TextureBinding.minFilterForResourcePath(resourcePath);
    }

    static int magFilterForResourcePath(final String resourcePath) {
        return TextureBinding.magFilterForResourcePath(resourcePath);
    }

    static boolean isVictorPixelFontTexture(final String resourcePath) {
        return TextureBinding.isVictorPixelFontTexture(resourcePath);
    }

    static boolean isManagedVictorFontTexture(final String resourcePath) {
        return TextureBinding.isManagedVictorFontTexture(resourcePath);
    }

    static boolean isManagedFontTexture(final String resourcePath) {
        return TextureBinding.isManagedFontTexture(resourcePath);
    }

    static boolean isSharpenedUiFontTexture(final String resourcePath) {
        return TextureBinding.isSharpenedUiFontTexture(resourcePath);
    }

    static boolean isFontAtlasWithoutMipmaps(final String resourcePath) {
        return TextureBinding.isFontAtlasWithoutMipmaps(resourcePath);
    }

    static String formatManagementSummary(final List<TextureCompositionReport.TextureEntry> entries,
                                          final long recentlyEvictedTextures,
                                          final long totalEvictedTextures) {
        return TextureManagementReport.formatManagementSummary(entries, recentlyEvictedTextures, totalEvictedTextures);
    }

    static long estimateTextureGpuBytes(final String resourcePath,
                                        final int imageWidth,
                                        final int imageHeight,
                                        final int textureWidth,
                                        final int textureHeight,
                                        final TextureCompressionSupport.Format compressionFormat) {
        return TextureUploader.estimateTextureGpuBytes(
                resourcePath, imageWidth, imageHeight, textureWidth, textureHeight, compressionFormat);
    }

    static File resolveManagedFile(final String resourcePath) {
        return TextureResourceLoader.resolveManagedFile(resourcePath);
    }

    static TextureConversionCache.TextureSourceFingerprint probeManagedFingerprint(final String originalPath,
                                                                                   final String normalizedPath) {
        return TextureResourceLoader.probeManagedFingerprint(originalPath, normalizedPath);
    }

    record ResolvedDeferredTexture(TextureConversionCache.CachedTextureData data,
                                   String sourceHash) {
    }
}
