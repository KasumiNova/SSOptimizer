package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import com.fs.graphics.font.FontGlyph;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;

public final class BitmapFontGlyphProvider implements GlyphProvider {

    private final BitmapFont font;

    public BitmapFontGlyphProvider(final BitmapFont font) {
        this.font = font;
    }

    @Override
    public GlyphMetrics glyph(final int codePoint) {
        final BitmapGlyph glyph = font.getGlyph(codePoint);
        if (glyph == null) {
            return null;
        }
        return new GlyphMetrics(
                glyph.getXOffset(), glyph.getXAdvance(), glyph.getBearingY(),
                glyph.getWidth(), glyph.getHeight(),
                glyph.getTexX(), glyph.getTexY(), glyph.getTexWidth(), glyph.getTexHeight(),
                0);
    }

    @Override
    public Integer kerning(final int prevCodePoint, final int codePoint) {
        final FontGlyph kerning = font.getKerning(prevCodePoint, codePoint);
        return kerning == null ? null : kerning.getId();
    }

    @Override
    public int nominalFontSize() {
        return font.getNominalFontSize();
    }

    @Override
    public int lineHeight() {
        return font.getLineHeight();
    }
}
