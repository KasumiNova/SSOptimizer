package github.kasuminova.ssoptimizer.common.loading;

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
                return TrackedResourceImage.cached(path, sourceHash, imageBytes, cached);
            }
        }

        final BufferedImage decoded;

        if (!isPng(imageBytes)) {
            decoded = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return TrackedResourceImage.wrap(path, sourceHash, decoded);
        }

        try {
            decoded = nativePngDecoder.decode(imageBytes);
        } catch (IOException | UnsupportedOperationException ignored) {
            return TrackedResourceImage.wrap(path, sourceHash, ImageIO.read(new ByteArrayInputStream(imageBytes)));
        }

        return TrackedResourceImage.wrap(path, sourceHash, decoded);
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