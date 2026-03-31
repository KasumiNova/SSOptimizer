package github.kasuminova.ssoptimizer.loading;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class TrackedResourceImage extends BufferedImage {
    private static final ColorModel RGB_COLOR_MODEL  = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).getColorModel();
    private static final ColorModel ARGB_COLOR_MODEL = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getColorModel();

    private final String                       resourcePath;
    private final String                       sourceHash;
    private final byte[]                       sourceBytes;
    private final int                          imageWidth;
    private final int                          imageHeight;
    private final boolean                      hasAlpha;
    private final TexturePixelConversionResult cachedConversionResult;

    private volatile BufferedImage delegate;

    private TrackedResourceImage(final BufferedImage delegate,
                                 final String resourcePath,
                                 final String sourceHash) {
        super(delegate.getColorModel(), delegate.getRaster(), delegate.isAlphaPremultiplied(), null);
        this.resourcePath = resourcePath;
        this.sourceHash = sourceHash;
        this.sourceBytes = null;
        this.imageWidth = delegate.getWidth();
        this.imageHeight = delegate.getHeight();
        this.hasAlpha = delegate.getColorModel().hasAlpha();
        this.cachedConversionResult = null;
        this.delegate = delegate;
    }

    private TrackedResourceImage(final String resourcePath,
                                 final String sourceHash,
                                 final byte[] sourceBytes,
                                 final TextureConversionCache.CachedTextureData cachedData) {
        super(1, 1, cachedData.hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        this.resourcePath = resourcePath;
        this.sourceHash = sourceHash;
        this.sourceBytes = sourceBytes;
        this.imageWidth = cachedData.imageWidth();
        this.imageHeight = cachedData.imageHeight();
        this.hasAlpha = cachedData.hasAlpha();
        this.cachedConversionResult = cachedData.conversionResult();
    }

    static BufferedImage wrap(final String resourcePath,
                              final String sourceHash,
                              final BufferedImage image) {
        if (image == null) {
            return null;
        }
        if (!TextureConversionCache.isEnabled()) {
            return image;
        }
        if (image instanceof TrackedResourceImage) {
            return image;
        }

        return new TrackedResourceImage(image, resourcePath != null ? resourcePath : "", sourceHash != null ? sourceHash : "");
    }

    static BufferedImage cached(final String resourcePath,
                                final String sourceHash,
                                final byte[] sourceBytes,
                                final TextureConversionCache.CachedTextureData cachedData) {
        if (!TextureConversionCache.isEnabled()) {
            return null;
        }
        return new TrackedResourceImage(resourcePath != null ? resourcePath : "", sourceHash, sourceBytes, cachedData);
    }

    static String computeSourceHash(final byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest unavailable", e);
        }
    }

    String resourcePath() {
        return resourcePath;
    }

    String sourceHash() {
        return sourceHash;
    }

    TexturePixelConversionResult cachedConversionResult() {
        return cachedConversionResult;
    }

    @Override
    public int getWidth() {
        return imageWidth;
    }

    @Override
    public int getHeight() {
        return imageHeight;
    }

    @Override
    public ColorModel getColorModel() {
        BufferedImage current = delegate;
        if (current != null) {
            return current.getColorModel();
        }
        return hasAlpha ? ARGB_COLOR_MODEL : RGB_COLOR_MODEL;
    }

    @Override
    public int getType() {
        BufferedImage current = delegate;
        if (current != null) {
            return current.getType();
        }
        return hasAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
    }

    @Override
    public boolean isAlphaPremultiplied() {
        BufferedImage current = delegate;
        return current != null && current.isAlphaPremultiplied();
    }

    @Override
    public Raster getData() {
        return materialize().getData();
    }

    @Override
    public WritableRaster copyData(final WritableRaster outRaster) {
        return materialize().copyData(outRaster);
    }

    @Override
    public WritableRaster getRaster() {
        return materialize().getRaster();
    }

    @Override
    public int getRGB(final int x, final int y) {
        return materialize().getRGB(x, y);
    }

    @Override
    public int[] getRGB(final int startX,
                        final int startY,
                        final int w,
                        final int h,
                        final int[] rgbArray,
                        final int offset,
                        final int scansize) {
        return materialize().getRGB(startX, startY, w, h, rgbArray, offset, scansize);
    }

    @Override
    public void setRGB(final int x, final int y, final int rgb) {
        materialize().setRGB(x, y, rgb);
    }

    @Override
    public void setRGB(final int startX,
                       final int startY,
                       final int w,
                       final int h,
                       final int[] rgbArray,
                       final int offset,
                       final int scansize) {
        materialize().setRGB(startX, startY, w, h, rgbArray, offset, scansize);
    }

    @Override
    public Graphics getGraphics() {
        return materialize().getGraphics();
    }

    @Override
    public Graphics2D createGraphics() {
        return materialize().createGraphics();
    }

    @Override
    public BufferedImage getSubimage(final int x, final int y, final int w, final int h) {
        return materialize().getSubimage(x, y, w, h);
    }

    @Override
    public void flush() {
        BufferedImage current = delegate;
        if (current != null) {
            current.flush();
        }
        super.flush();
    }

    private BufferedImage materialize() {
        BufferedImage current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = delegate;
            if (current != null) {
                return current;
            }
            try {
                current = FastResourceImageDecoder.decodeUntracked(sourceBytes);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to materialize tracked resource image: " + resourcePath, e);
            }
            delegate = current;
            return current;
        }
    }
}