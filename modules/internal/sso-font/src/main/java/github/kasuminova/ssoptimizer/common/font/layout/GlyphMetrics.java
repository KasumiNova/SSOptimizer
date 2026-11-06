package github.kasuminova.ssoptimizer.common.font.layout;

public record GlyphMetrics(
        float xOffset,
        float xAdvance,
        float bearingY,
        float width,
        float height,
        float texX,
        float texY,
        float texWidth,
        float texHeight,
        int textureId) {
}
