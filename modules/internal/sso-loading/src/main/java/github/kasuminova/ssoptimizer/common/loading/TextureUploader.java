package github.kasuminova.ssoptimizer.common.loading;

import com.fs.graphics.TextureLoader;
import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.List;

/**
 * 纹理的 GL 上传、压缩纹理上传与压缩调度、mipmap 判定和显存估算。不持有驻留状态，纹理 ID 经 TextureResidency 读写。
 */
final class TextureUploader {
    private static final Logger LOGGER = Logger.getLogger(TextureUploader.class);
    private static final int TARGET_2D = 3553;
    private static final int INTERNAL_FORMAT_RGBA = 6408;
    private static final int FORMAT_RGB = 6407;
    private static final int FORMAT_RGBA = 6408;
    private static final int FILTER_LINEAR_MIPMAP_LINEAR = 9987;
    private static final int GENERATE_MIPMAP = 33169;
    private static final int TYPE_UNSIGNED_BYTE = 5121;

    private TextureUploader() {
    }

    static TextureCompressionSupport.Format uploadConverted(
            final com.fs.graphics.TextureObject texture,
            final int target,
            final String resourcePath,
            final String sourceHash,
            final TextureConversionCache.CachedTextureData cached) {
        final TexturePixelConversionResult result = cached.conversionResult();
        int textureId = TextureResidency.readTextureId(texture, -1);
        if (textureId == -1) {
            final IntBuffer ids = BufferUtils.createIntBuffer(1);
            GL11.glGenTextures(ids);
            textureId = ids.get(0);
            TextureResidency.setTextureId(texture, textureId);
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
            GL11.glTexParameteri(target, 10240, TextureBinding.magFilterForResourcePath(resourcePath));
            GL11.glTexParameteri(TARGET_2D, GENERATE_MIPMAP, 1);
        } else {
            GL11.glTexParameteri(target, 10241, TextureBinding.minFilterForResourcePath(resourcePath));
            GL11.glTexParameteri(target, 10240, TextureBinding.magFilterForResourcePath(resourcePath));
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
    static void uploadCompressedLevels(final int target,
                                       final String resourcePath,
                                       final boolean generateMipmaps,
                                       final SsobcContainer container) {
        final List<SsobcContainer.Level> levels = container.levels();
        final boolean mipChainUploaded = generateMipmaps && levels.size() > 1;
        if (generateMipmaps && !mipChainUploaded) {
            LOGGER.warn("[SSOptimizer] 压缩纹理缺少 mip 链（" + resourcePath
                    + "，levels=" + levels.size() + "），min filter 回退 LINEAR");
        }

        GL11.glTexParameteri(target, 10241,
                mipChainUploaded ? FILTER_LINEAR_MIPMAP_LINEAR : TextureBinding.minFilterForResourcePath(resourcePath));
        GL11.glTexParameteri(target, 10240, TextureBinding.magFilterForResourcePath(resourcePath));
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

    private static SsobcContainer compressSynchronously(
            final String resourcePath,
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
            LOGGER.warn("[SSOptimizer] Texture compression failed at pixel-buffer conversion for " + resourcePath
                    + " (" + result.textureWidth() + 'x' + result.textureHeight()
                    + "); falling back to uncompressed upload");
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
            LOGGER.warn("[SSOptimizer] Synchronous native texture compression failed for " + resourcePath
                    + " (" + result.textureWidth() + 'x' + result.textureHeight()
                    + "); falling back to uncompressed upload");
            return null;
        }

        CompressedTextureCache.store(
                new CompressedTextureCache.Key(sourceHash,
                        result.textureWidth(), result.textureHeight(), generateMipmaps, format,
                        TextureCompressionScheduler.resolveQuality(resourcePath)),
                containerBytes, resourcePath,
                TextureResourceLoader.probeManagedFingerprint(resourcePath, resourcePath));
        return SsobcContainer.parse(containerBytes);
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

    static void maybeScheduleDeferredPrepass(final String resourcePath,
                                             final String sourceHash,
                                             final TextureResourceLoader.LazyTextureMetadata metadata) {
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
        if (TextureBinding.isFontAtlasWithoutMipmaps(resourcePath)) {
            return false;
        }
        if (imageWidth <= 1024 && imageHeight <= 1024) {
            return true;
        }
        return TextureLoader.specialMipmapSet.contains(resourcePath);
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
}
