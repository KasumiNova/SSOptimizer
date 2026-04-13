package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.font.OriginalGameFontOverrides;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Startup image decoder for resource loads. PNG assets try the native backend
 * first, while non-PNG assets and native PNG failures fall back to ImageIO.
 */
public final class FastResourceImageDecoder {
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
        // 字体覆盖路径由 OriginalGameFontOverrides 在内存中提供 PNG 数据，
        // 但磁盘上仍然是原版（或汉化包）的 PNG 文件。基于磁盘指纹的
        // TextureConversionCache 会命中旧缓存，返回原版像素数据而非 SSO 生成的像素，
        // 导致 .fnt 坐标与纹理内容不匹配、渲染乱码。
        // 因此对字体覆盖路径跳过 probeFingerprint，强制走 InputStream 解码路径。
        final boolean fontOverride = OriginalGameFontOverrides.isEnabled()
                && OriginalGameFontOverrides.isOverriddenPath(
                        OriginalGameFontOverrides.normalize(path));
        final TextureConversionCache.TextureSourceFingerprint sourceFingerprint =
                (!fontOverride && TextureConversionCache.isEnabled())
                        ? TextureConversionCache.probeFingerprint(path)
                        : null;
        if (sourceFingerprint != null) {
            final TextureConversionCache.ResourceCacheHit resourceCacheHit = TextureConversionCache.loadByResourcePath(path, sourceFingerprint);
            if (resourceCacheHit != null) {
                return TrackedResourceImage.cached(
                        path,
                        resourceCacheHit.sourceHash(),
                        null,
                        resourceCacheHit.cachedData(),
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
            final TextureConversionCache.CachedTextureData cached = TextureConversionCache.load(sourceHash);
            if (cached != null) {
                return TrackedResourceImage.cached(path, sourceHash, imageBytes, cached, sourceFingerprint);
            }
        }

        final BufferedImage decoded;

        if (!isPng(imageBytes)) {
            decoded = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return TrackedResourceImage.wrap(path, sourceHash, decoded, sourceFingerprint);
        }

        try {
            decoded = nativePngDecoder.decode(imageBytes);
        } catch (IOException | UnsupportedOperationException ignored) {
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
        } catch (IOException | UnsupportedOperationException ignored) {
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
}