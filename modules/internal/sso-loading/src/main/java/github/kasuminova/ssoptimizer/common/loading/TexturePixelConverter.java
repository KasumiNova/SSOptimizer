package github.kasuminova.ssoptimizer.common.loading;

import org.lwjgl.BufferUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

public final class TexturePixelConverter {
    private static final Color WHITE = Color.white;

    private TexturePixelConverter() {
    }

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

    // 仅在 BC 缓存已写入时抑制 RGBA 缓存写入，已投递但未完成时仍保留。
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