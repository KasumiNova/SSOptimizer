package github.kasuminova.ssoptimizer.common.loading;

import org.lwjgl.BufferUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/**
 * Converts {@link BufferedImage} data to the direct NIO texture layout expected
 * by the engine, but avoids the original per-pixel {@code Raster.getPixel()}
 * path by bulk-reading packed ARGB values via {@link BufferedImage#getRGB}.
 */
public final class TexturePixelConverter {
    private static final Color WHITE = Color.white;

    private TexturePixelConverter() {
    }

    /**
     * 转换为引擎期望的 direct NIO 像素布局。
     * <p>
     * 缓存命中短路由调用方负责（如预加载 worker 直接消费元数据）；走到这里的
     * 一律执行真实像素转换并尝试写入磁盘缓存（已存在条目时 {@code store} 自动跳过）。
     * 例外：适用 GPU 压缩且 BC 压缩缓存已写入的纹理抑制 ssotex 写入（见
     * {@link #isCompressedCacheBacked}）。对仅持元数据的 {@link TrackedResourceImage}
     * 调用会触发实体化解码后再转换。
     */
    public static TexturePixelConversionResult convert(final BufferedImage image) {
        if (image instanceof TrackedResourceImage trackedImage) {
            final TexturePixelConversionResult converted = convertUncached(image);
            if (!isCompressedCacheBacked(trackedImage, converted)) {
                TextureConversionCache.store(trackedImage, converted);
            }
            return converted;
        }

        return convertUncached(image);
    }

    /**
     * ssotex 写入抑制（设计 §4.4）：BC 压缩缓存是有损「最终形态」缓存，命中后同源的
     * RGBA 解码缓存不再被读取，继续写入纯属磁盘浪费。
     * <p>
     * 抑制条件是「BC 缓存已写入」（{@link CompressedTextureCache#load} 命中），
     * 而非「已投递压缩」——首轮压缩完成前 ssotex 照常写入，否则下次启动还得重解码。
     * 顺带收益：命中的 BC 条目被拉进内存 LRU，后续上传零磁盘 I/O。
     */
    private static boolean isCompressedCacheBacked(final TrackedResourceImage image,
                                                   final TexturePixelConversionResult result) {
        final String sourceHash = image.sourceHash();
        if (sourceHash == null || sourceHash.isBlank()) {
            return false;
        }

        final TextureCompressionSupport.Format format = TextureCompressionEligibility.selectFormat(
                image.resourcePath(),
                result.textureWidth(),
                result.textureHeight(),
                result.alphaKind(),
                TextureCompressionSupport.preferredFormat());
        if (format == TextureCompressionSupport.Format.NONE) {
            return false;
        }

        final boolean mipmaps = LazyTextureManager.shouldGenerateMipmaps(
                image.resourcePath(), image.getWidth(), image.getHeight());
        return CompressedTextureCache.load(new CompressedTextureCache.Key(
                sourceHash, result.textureWidth(), result.textureHeight(), mipmaps, format,
                TextureCompressionScheduler.resolveQuality(image.resourcePath()))) != null;
    }

    private static TexturePixelConversionResult convertUncached(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        final int textureWidth = TextureDimensionSupport.textureDimension(width);
        final int textureHeight = TextureDimensionSupport.textureDimension(height);
        final boolean hasAlpha = image.getColorModel().hasAlpha();
        final int channels = hasAlpha ? 4 : 3;

        final ByteBuffer buffer = BufferUtils.createByteBuffer(textureWidth * textureHeight * channels);
        buffer.position(0);
        buffer.limit(buffer.capacity());

        final int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
        final int[] histogramR = new int[256];
        final int[] histogramG = new int[256];
        final int[] histogramB = new int[256];

        long sumR = 0L;
        long sumG = 0L;
        long sumB = 0L;
        int sampledPixels = 0;
        // 实际像素 alpha 内容统计（与转换同一遍历，零额外扫描）：
        // 出现过 0 以外的非 255 → FULL；出现过 0 → 至少 BINARY
        boolean sawTransparentPixel = false;
        boolean sawPartialAlpha = false;

        for (int y = 0; y < height; y++) {
            final int srcRow = (height - 1 - y) * width;
            final int dstRow = y * textureWidth * channels;

            for (int x = 0; x < width; x++) {
                final int argb = pixels[srcRow + x];
                final int alpha = (argb >>> 24) & 0xFF;
                final int red = (argb >>> 16) & 0xFF;
                final int green = (argb >>> 8) & 0xFF;
                final int blue = argb & 0xFF;

                if (alpha == 0) {
                    sawTransparentPixel = true;
                } else if (alpha != 255) {
                    sawPartialAlpha = true;
                }

                if (hasAlpha && alpha == 0) {
                    continue;
                }

                final int index = dstRow + x * channels;
                buffer.put(index, (byte) red);
                buffer.put(index + 1, (byte) green);
                buffer.put(index + 2, (byte) blue);
                if (hasAlpha) {
                    buffer.put(index + 3, (byte) alpha);
                }

                sumR += red;
                sumG += green;
                sumB += blue;
                histogramR[red]++;
                histogramG[green]++;
                histogramB[blue]++;
                sampledPixels++;
            }
        }

        final AlphaKind alphaKind = alphaKindOf(hasAlpha, sawTransparentPixel, sawPartialAlpha);

        if (sampledPixels == 0) {
            return new TexturePixelConversionResult(buffer, textureWidth, textureHeight,
                    WHITE, WHITE, WHITE, alphaKind);
        }

        final Color averageColor = new Color(
                clampChannel((int) (sumR / sampledPixels)),
                clampChannel((int) (sumG / sampledPixels)),
                clampChannel((int) (sumB / sampledPixels)),
                255);

        final float halfThreshold = sampledPixels * 0.5f;
        final Color upperHalfColor = new Color(
                clampChannel(percentileFromHigh(histogramR, halfThreshold)),
                clampChannel(percentileFromHigh(histogramG, halfThreshold)),
                clampChannel(percentileFromHigh(histogramB, halfThreshold)),
                255);

        final Color lowerHalfColor = new Color(
                clampChannel(percentileFromLow(histogramR, halfThreshold)),
                clampChannel(percentileFromLow(histogramG, halfThreshold)),
                clampChannel(percentileFromHigh(histogramB, halfThreshold)),
                255);

        return new TexturePixelConversionResult(buffer, textureWidth, textureHeight,
                averageColor, upperHalfColor, lowerHalfColor, alphaKind);
    }

    /** 实际像素 alpha 内容分类（纯函数，单测直调）。 */
    static AlphaKind alphaKindOf(final boolean hasAlphaChannel,
                                 final boolean sawTransparentPixel,
                                 final boolean sawPartialAlpha) {
        if (!hasAlphaChannel) {
            return AlphaKind.OPAQUE;
        }
        if (sawPartialAlpha) {
            return AlphaKind.FULL;
        }
        return sawTransparentPixel ? AlphaKind.BINARY : AlphaKind.OPAQUE;
    }

    private static int percentileFromLow(final int[] histogram, final float threshold) {
        float accumulated = 0.0f;
        for (int i = 0; i <= 255; i++) {
            accumulated += histogram[i];
            if (accumulated >= threshold) {
                return i;
            }
        }
        return 0;
    }

    private static int percentileFromHigh(final int[] histogram, final float threshold) {
        float accumulated = 0.0f;
        for (int i = 255; i >= 0; i--) {
            float contribution = histogram[i];
            if (accumulated + contribution >= threshold) {
                return i;
            }
            accumulated += contribution;
        }
        return 0;
    }

    private static int clampChannel(final int value) {
        return Math.max(0, Math.min(255, value));
    }
}