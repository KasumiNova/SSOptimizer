package github.kasuminova.ssoptimizer.common.font.layout;

public record GlyphQuad(
        float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2,
        float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4,
        int color,
        int textureId) {
}
