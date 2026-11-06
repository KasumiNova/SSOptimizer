package github.kasuminova.ssoptimizer.common.font.atlas;

public record GlyphSlot(
        GlyphAtlasPage page,
        int x,
        int y,
        int width,
        int height,
        int originX,
        int originY,
        float texX,
        float texY,
        float texWidth,
        float texHeight) {

    public int textureId() {
        return page.textureId();
    }
}
