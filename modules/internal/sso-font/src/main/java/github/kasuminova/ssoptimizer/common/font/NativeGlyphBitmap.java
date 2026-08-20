package github.kasuminova.ssoptimizer.common.font;

/**
 * Immutable glyph bitmap payload returned by the optional native rasterizer.
 */
public record NativeGlyphBitmap(int width,
                                int height,
                                int[] argbPixels,
                                int xOffset,
                                int yOffset,
                                int xAdvance) {
    public NativeGlyphBitmap {
        argbPixels = argbPixels == null ? null : argbPixels.clone();
    }

    @Override
    public int[] argbPixels() {
        return argbPixels == null ? null : argbPixels.clone();
    }

    public boolean hasImage() {
        return width > 0
                && height > 0
                && argbPixels != null
                && argbPixels.length >= width * height;
    }
}