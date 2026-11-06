package github.kasuminova.ssoptimizer.common.font.atlas;

@FunctionalInterface
public interface GlyphRasterizer {

    github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap rasterize(
            int codePoint, int baseline, float strokeWidthPx);
}
