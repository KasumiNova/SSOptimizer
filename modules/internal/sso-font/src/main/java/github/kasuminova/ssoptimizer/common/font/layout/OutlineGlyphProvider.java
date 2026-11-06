package github.kasuminova.ssoptimizer.common.font.layout;

public interface OutlineGlyphProvider extends GlyphProvider {

    boolean synthesizesOutline();

    GlyphMetrics strokedGlyph(int codePoint, float strokeWidthLogicalPx);

    float currentBucketScale();
}
