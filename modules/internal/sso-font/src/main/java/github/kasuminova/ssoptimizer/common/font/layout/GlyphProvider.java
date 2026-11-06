package github.kasuminova.ssoptimizer.common.font.layout;

public interface GlyphProvider {

    GlyphMetrics glyph(int codePoint);

    Integer kerning(int prevCodePoint, int codePoint);

    int nominalFontSize();

    int lineHeight();

    /** 仅允许逻辑线程调用；实现可在返回值上保存本次布局的尺寸档位状态。 */
    default GlyphProvider forScale(final float scale) {
        return this;
    }

    default void flushPendingUploads() {
    }

    default boolean usesAtlasTexture() {
        return false;
    }
}
