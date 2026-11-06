package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import com.fs.graphics.TextureManager;
import com.fs.util.ResourceLoader;
import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;

final class TextureResourceLoader {
    private static final Logger LOGGER = Logger.getLogger(TextureResourceLoader.class);
    private static final int TARGET_2D = 3553;
    private static final int INTERNAL_FORMAT_RGBA = 6408;

    private TextureResourceLoader() {
    }

    static com.fs.graphics.TextureObject loadTexture(
            final TextureLoader loader,
            final HashMap<String, com.fs.graphics.TextureObject> textureCache,
            final String resourcePath) throws IOException {
        TextureCompressionSupport.preferredFormat();
        final com.fs.graphics.TextureObject cached = textureCache.get(resourcePath);
        if (cached != null) {
            TextureContextTracker.ensureContextBoundTextureTracked(cached, resourcePath);
            return cached;
        }

        final String normalizedPath = normalizeResourcePath(resourcePath);
        final String effectivePath = normalizedPath.isEmpty() ? resourcePath : normalizedPath;
        if (!effectivePath.equals(resourcePath)) {
            final com.fs.graphics.TextureObject normalizedCached = textureCache.get(effectivePath);
            if (normalizedCached != null) {
                textureCache.put(resourcePath, normalizedCached);
                TextureContextTracker.ensureContextBoundTextureTracked(normalizedCached, effectivePath);
                return normalizedCached;
            }
        }

        if (isOriginalLazyModeEnabled()) {
            final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
            texture.setDeferredLoadingEnabled(true);
            cacheTexture(textureCache, resourcePath, effectivePath, texture);
            return TextureContextTracker.markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        if (!TextureResidency.isEnabled()) {
            return TextureContextTracker.markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        if (effectivePath == null || effectivePath.isEmpty()) {
            return TextureContextTracker.markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, resourcePath, resourcePath), resourcePath);
        }

        final TexturePreparationRegistry.Prepared prepared = TexturePreparationRegistry.await(effectivePath);
        if (prepared != null) {
            return loadPreparedTexture(loader, textureCache, resourcePath, effectivePath, prepared);
        }

        final SourceSnapshot source = readSource(effectivePath, resourcePath);
        final LazyTextureMetadata metadata = buildMetadata(effectivePath, source);
        if (metadata == null) {
            return TextureContextTracker.markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        final boolean defer = TextureResidency.shouldDefer(
                effectivePath, source.sourceByteLength(), metadata.estimatedGpuBytes);
        final boolean trackResidency = TextureResidency.shouldTrackResidency(
                effectivePath, source.sourceByteLength(), metadata.estimatedGpuBytes);
        if (!trackResidency) {
            return TextureContextTracker.markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, resourcePath), effectivePath);
        }

        final long now = System.nanoTime();
        if (!defer) {
            final com.fs.graphics.TextureObject texture = eagerLoad(loader, textureCache, effectivePath, resourcePath);
            TextureResidency.trackResident(texture, effectivePath, source.sourceHash(), metadata, now, true);
            return TextureContextTracker.markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
        applyMetadata(texture, metadata);
        cacheTexture(textureCache, resourcePath, effectivePath, texture);
        TextureResidency.trackPending(texture, effectivePath, source.sourceHash(), metadata, now, true);
        TextureResidency.maybeScheduleDeferredPrepass(effectivePath, source.sourceHash(), metadata);
        return TextureContextTracker.markTextureLoadedInCurrentContext(texture, effectivePath);
    }

    static boolean isOriginalLazyModeEnabled() {
        return TextureManager.isLazyLoadingEnabled();
    }

    static LazyTextureManager.ResolvedDeferredTexture resolveDeferredTextureData(
            final String resourcePath,
            final String registeredSourceHash) {
        final TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(registeredSourceHash);
        if (cached != null) {
            return new LazyTextureManager.ResolvedDeferredTexture(cached, registeredSourceHash);
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
        return new LazyTextureManager.ResolvedDeferredTexture(
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

    private static com.fs.graphics.TextureObject eagerLoad(
            final TextureLoader loader,
            final HashMap<String, com.fs.graphics.TextureObject> textureCache,
            final String loadPath,
            final String requestedPath) throws IOException {
        final com.fs.graphics.TextureObject texture = loader.loadTextureData(
                null,
                loadPath,
                TARGET_2D,
                INTERNAL_FORMAT_RGBA,
                TextureBinding.minFilterForResourcePath(loadPath),
                TextureBinding.magFilterForResourcePath(loadPath),
                false
        );
        cacheTexture(textureCache, requestedPath, loadPath, texture);
        return texture;
    }

    private static com.fs.graphics.TextureObject loadPreparedTexture(
            final TextureLoader loader,
            final HashMap<String, com.fs.graphics.TextureObject> textureCache,
            final String requestedPath,
            final String effectivePath,
            final TexturePreparationRegistry.Prepared prepared) throws IOException {
        final TextureConversionCache.CachedTextureMetadata preparedMetadata = prepared.metadata();
        final LazyTextureMetadata metadata = metadataFrom(effectivePath, preparedMetadata);
        final int sourceBytes = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, prepared.sourceByteLength()));
        final boolean defer = TextureResidency.shouldDefer(effectivePath, sourceBytes, metadata.estimatedGpuBytes);
        final boolean trackResidency = TextureResidency.shouldTrackResidency(
                effectivePath, sourceBytes, metadata.estimatedGpuBytes);
        final long now = System.nanoTime();

        final com.fs.graphics.TextureObject texture = new com.fs.graphics.TextureObject(TARGET_2D, -1, effectivePath);
        applyMetadata(texture, metadata);

        if (trackResidency && defer) {
            cacheTexture(textureCache, requestedPath, effectivePath, texture);
            TextureResidency.trackPending(texture, effectivePath, prepared.sourceHash(), metadata, now, true);
            TextureResidency.maybeScheduleDeferredPrepass(effectivePath, prepared.sourceHash(), metadata);
            return TextureContextTracker.markTextureLoadedInCurrentContext(texture, effectivePath);
        }

        final TextureConversionCache.CachedTextureData pixels = TextureConversionCache.load(prepared.sourceHash());
        if (pixels == null) {
            LOGGER.warn("[SSOptimizer] Prepared texture cache entry missing for "
                    + effectivePath + ", falling back to eager load");
            return TextureContextTracker.markTextureLoadedInCurrentContext(
                    eagerLoad(loader, textureCache, effectivePath, requestedPath), effectivePath);
        }

        final TextureCompressionSupport.Format usedFormat = TextureResidency.uploadConverted(
                texture, TARGET_2D, effectivePath, prepared.sourceHash(), pixels);
        cacheTexture(textureCache, requestedPath, effectivePath, texture);
        if (trackResidency) {
            TextureResidency.trackResident(
                    texture, effectivePath, prepared.sourceHash(), metadata, now, true, usedFormat);
        }
        return TextureContextTracker.markTextureLoadedInCurrentContext(texture, effectivePath);
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

    private static LazyTextureMetadata buildMetadata(final String resourcePath,
                                                     final SourceSnapshot source) throws IOException {
        if (source.cachedMetadata() != null) {
            return metadataFrom(resourcePath, source.cachedMetadata());
        }

        final TextureConversionCache.CachedTextureMetadata cached =
                TextureConversionCache.loadMetadata(source.sourceHash());
        if (cached != null) {
            return metadataFrom(resourcePath, cached);
        }

        final BufferedImage decoded = FastResourceImageDecoder.decodeUntracked(source.sourceBytes());
        if (decoded == null) {
            return null;
        }

        final BufferedImage tracked = TrackedResourceImage.wrap(
                resourcePath, source.sourceHash(), decoded, source.sourceFingerprint());
        final TexturePixelConversionResult result = TexturePixelConverter.convert(tracked);
        return metadataFrom(resourcePath,
                tracked.getWidth(),
                tracked.getHeight(),
                tracked.getColorModel().hasAlpha(),
                result);
    }

    static void applyMetadata(final com.fs.graphics.TextureObject texture,
                              final LazyTextureMetadata metadata) {
        texture.setImageHeight(metadata.imageHeight);
        texture.setImageWidth(metadata.imageWidth);
        texture.setTextureWidth(metadata.textureWidth);
        texture.setTextureHeight(metadata.textureHeight);
        texture.setAverageColor(metadata.averageColor);
        texture.setUpperHalfColor(metadata.upperHalfColor);
        texture.setLowerHalfColor(metadata.lowerHalfColor);
    }

    static String normalizeResourcePath(final String resourcePath) {
        if (resourcePath == null) {
            return "";
        }
        String normalized = resourcePath.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
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

    static TextureConversionCache.TextureSourceFingerprint probeManagedFingerprint(
            final String originalPath,
            final String normalizedPath) {
        File resolved = resolveManagedFile(originalPath);
        if (resolved == null && !normalizedPath.equals(originalPath)) {
            resolved = resolveManagedFile(normalizedPath);
        }
        return resolved != null ? TextureConversionCache.probeFingerprint(resolved.getAbsolutePath()) : null;
    }

    static LazyTextureMetadata metadataFrom(final String resourcePath,
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
                TextureResidency.estimateTextureGpuBytes(resourcePath,
                        metadata.imageWidth(),
                        metadata.imageHeight(),
                        metadata.textureWidth(),
                        metadata.textureHeight(),
                        TextureCompressionSupport.Format.NONE)
        );
    }

    static LazyTextureMetadata metadataFrom(final String resourcePath,
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
                TextureResidency.estimateTextureGpuBytes(resourcePath,
                        imageWidth,
                        imageHeight,
                        result.textureWidth(),
                        result.textureHeight(),
                        TextureCompressionSupport.Format.NONE)
        );
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

    static final class LazyTextureMetadata {
        final int imageWidth;
        final int imageHeight;
        final boolean hasAlpha;
        final AlphaKind alphaKind;
        final int textureWidth;
        final int textureHeight;
        final Color averageColor;
        final Color upperHalfColor;
        final Color lowerHalfColor;
        final long estimatedGpuBytes;

        LazyTextureMetadata(final int imageWidth,
                            final int imageHeight,
                            final boolean hasAlpha,
                            final AlphaKind alphaKind,
                            final int textureWidth,
                            final int textureHeight,
                            final Color averageColor,
                            final Color upperHalfColor,
                            final Color lowerHalfColor,
                            final long estimatedGpuBytes) {
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.hasAlpha = hasAlpha;
            this.alphaKind = alphaKind;
            this.textureWidth = textureWidth;
            this.textureHeight = textureHeight;
            this.averageColor = averageColor;
            this.upperHalfColor = upperHalfColor;
            this.lowerHalfColor = lowerHalfColor;
            this.estimatedGpuBytes = estimatedGpuBytes;
        }
    }

    private static FontResourceOverride fontResourceOverride() {
        return ServiceRegistry.getOrNull(FontResourceOverride.class);
    }
}
