package github.kasuminova.ssoptimizer.common.font;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import com.fs.graphics.font.FontGlyph;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphMetrics;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;

/**
 * {@link GlyphProvider} 的位图字体实现：直接读取原版 {@link BitmapFont} 的公开 getter
 * （glyph 数组 / kerning 表 / 名义字号 / 行高），逐次调用组装 {@link GlyphMetrics}。
 * <p>
 * 动机：P1/P2 阶段字形源仍是原版 fnt 位图（含现有 TTF 覆盖生成的产物），
 * 布局引擎经本适配器对接；P3 动态图集上线后由 TTF 源实现平行替换。
 */
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
                glyph.getTexX(), glyph.getTexY(), glyph.getTexWidth(), glyph.getTexHeight());
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
