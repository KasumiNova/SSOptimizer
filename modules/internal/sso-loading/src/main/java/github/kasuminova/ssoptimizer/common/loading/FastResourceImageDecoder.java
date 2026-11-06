package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.api.font.FontResourceOverride;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import org.apache.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public final class FastResourceImageDecoder {
    private static final Logger LOGGER = Logger.getLogger(FastResourceImageDecoder.class);
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };

    private FastResourceImageDecoder() {
    }

    public static BufferedImage decode(final String path, final InputStream inputStream) throws IOException {
        return decode(path, inputStream, NativePngDecoder::decode);
    }

    static BufferedImage decode(final String path,
                                final InputStream inputStream,
                                final PngByteDecoder nativePngDecoder) throws IOException {
        // 字体覆盖仅替换内存 PNG，必须跳过磁盘指纹缓存，避免命中原版像素。
        final FontResourceOverride fontOverrideService = fontResourceOverride();
        final boolean fontOverride = fontOverrideService != null
                && fontOverrideService.isEnabled()
                && fontOverrideService.isOverriddenPath(fontOverrideService.normalize(path));
        // 指纹必须经模组覆盖优先的合并视图解析，与实际 InputStream 来源一致。
        final TextureConversionCache.TextureSourceFingerprint sourceFingerprint =
                (!fontOverride && TextureConversionCache.isEnabled())
                        ? LazyTextureManager.probeManagedFingerprint(path, path)
                        : null;
        if (sourceFingerprint != null) {
            final TextureConversionCache.ResourceMetadataHit metadataHit =
                    TextureConversionCache.probeMetadataByResourcePath(path, sourceFingerprint);
            if (metadataHit != null) {
                return TrackedResourceImage.cached(
                        path,
                        metadataHit.sourceHash(),
                        null,
                        metadataHit.metadata(),
                        sourceFingerprint
                );
            }
        }

        if (inputStream == null) {
            return null;
        }

        final BufferedInputStream buffered = inputStream instanceof BufferedInputStream bis
                ? bis
                : new BufferedInputStream(inputStream);

        final byte[] imageBytes = buffered.readAllBytes();
        final String sourceHash = TextureConversionCache.isEnabled()
                ? TrackedResourceImage.computeSourceHash(imageBytes)
                : null;
        if (sourceHash != null) {
            final TextureConversionCache.CachedTextureMetadata metadata = TextureConversionCache.loadMetadata(sourceHash);
            if (metadata != null) {
                return TrackedResourceImage.cached(path, sourceHash, imageBytes, metadata, sourceFingerprint);
            }
        }

        final BufferedImage decoded;

        if (!isPng(imageBytes)) {
            decoded = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return TrackedResourceImage.wrap(path, sourceHash, decoded, sourceFingerprint);
        }

        try {
            decoded = nativePngDecoder.decode(imageBytes);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.warn("[SSOptimizer] Native PNG decode failed for " + path + ", using ImageIO", e);
            return TrackedResourceImage.wrap(path, sourceHash, ImageIO.read(new ByteArrayInputStream(imageBytes)), sourceFingerprint);
        }

        return TrackedResourceImage.wrap(path, sourceHash, decoded, sourceFingerprint);
    }

    static BufferedImage decodeUntracked(final byte[] imageBytes) throws IOException {
        if (!isPng(imageBytes)) {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }

        try {
            return NativePngDecoder.decode(imageBytes);
        } catch (IOException | UnsupportedOperationException e) {
            LOGGER.warn("[SSOptimizer] Native PNG decode failed for an untracked resource, using ImageIO", e);
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        }
    }

    private static boolean isPng(final byte[] imageBytes) {
        if (imageBytes.length < PNG_SIGNATURE.length) {
            return false;
        }

        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (imageBytes[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    @FunctionalInterface
    interface PngByteDecoder {
        BufferedImage decode(byte[] imageBytes) throws IOException;
    }

    private static FontResourceOverride fontResourceOverride() {
        return ServiceRegistry.getOrNull(FontResourceOverride.class);
    }
}
