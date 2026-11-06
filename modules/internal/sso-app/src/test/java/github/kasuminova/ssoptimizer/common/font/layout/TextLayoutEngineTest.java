package github.kasuminova.ssoptimizer.common.font.layout;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextLayoutEngineTest {

    private static final GlyphMetrics GLYPH_A = new GlyphMetrics(1, 12, 11, 10, 12, 0.10f, 0.10f, 0.05f, 0.06f, 0);
    private static final GlyphMetrics GLYPH_B = new GlyphMetrics(0, 11, 11, 9, 12, 0.20f, 0.10f, 0.05f, 0.06f, 0);
    private static final GlyphMetrics GLYPH_Q = new GlyphMetrics(0, 8, 10, 7, 11, 0.30f, 0.10f, 0.05f, 0.06f, 0);
    private static final GlyphMetrics GLYPH_UL = new GlyphMetrics(0, 10, -2, 8, 2, 0.40f, 0.10f, 0.05f, 0.06f, 0);
    private static final GlyphMetrics GLYPH_BRACE = new GlyphMetrics(0, 4, 0, 0, 0, 0.50f, 0.10f, 0f, 0f, 0);

    private static final class FakeGlyphs implements GlyphProvider {
        final Map<Integer, GlyphMetrics> glyphs = new HashMap<>();
        final Map<Long, Integer> kernings = new HashMap<>();
        final boolean hasFallback;

        FakeGlyphs(boolean hasFallback) {
            this.hasFallback = hasFallback;
            glyphs.put(65, GLYPH_A);
            glyphs.put(66, GLYPH_B);
            glyphs.put(95, GLYPH_UL);
            glyphs.put(123, GLYPH_BRACE);
            if (hasFallback) {
                glyphs.put(63, GLYPH_Q);
            }
        }

        FakeGlyphs kern(int prev, int cur, int value) {
            kernings.put(((long) prev << 32) | cur, value);
            return this;
        }

        @Override
        public GlyphMetrics glyph(int codePoint) {
            return glyphs.get(codePoint);
        }

        @Override
        public Integer kerning(int prevCodePoint, int codePoint) {
            return kernings.get(((long) prevCodePoint << 32) | codePoint);
        }

        @Override
        public int nominalFontSize() {
            return 15;
        }

        @Override
        public int lineHeight() {
            return 18;
        }
    }

    private static FakeGlyphs font() {
        return new FakeGlyphs(true).kern(65, 66, -2);
    }

    private static TextRenderState.Builder state(String text) {
        return TextRenderState.builder(text).draw(100f, 200f).fontSize(15f);
    }

    private static GlyphQuad onlyQuadOfMainPass(List<TextPass> passes, int quadIndex) {
        return passes.get(passes.size() - 1).quads().get(quadIndex);
    }

    @Test
    void basicLayoutMatchesVanillaGeometry() {
        List<TextPass> passes = TextLayoutEngine.layout(state("AB").build(), font());
        assertEquals(1, passes.size(), "无边框/阴影/compact 时只有主 pass");
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());

        GlyphQuad a = quads.get(0);
        assertEquals(101f, a.x1());
        assertEquals(189f, a.y1());
        assertEquals(101f, a.x2());
        assertEquals(177f, a.y2());
        assertEquals(111f, a.x3());
        assertEquals(177f, a.y3());
        assertEquals(111f, a.x4());
        assertEquals(189f, a.y4());
        assertEquals(0.10f, a.u1());
        assertEquals(0.16f, a.v1(), 1e-6f);
        assertEquals(0xFFFFFFFF, a.color());

        GlyphQuad b = quads.get(1);
        assertEquals(111f, b.x1());
        assertEquals(120f, b.x3());
        assertEquals(189f, b.y1());
    }

    @Test
    void newlineQuirkFixedTenPixelsAtSize18And15() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A\n\nB").fontSize(18f).build(), font());
        GlyphQuad b = onlyQuadOfMainPass(passes, 1);
        float scale = 18f / 15f;
        float lineY = -scale * 18f - 10f;
        assertEquals(200f - scale * 11f + lineY, b.y1(), 1e-4f);

        passes = TextLayoutEngine.layout(state("A\n\nB").fontSize(15f).build(), font());
        b = onlyQuadOfMainPass(passes, 1);
        assertEquals(200f - 11f - 18f - 10f, b.y1(), 1e-4f);

        passes = TextLayoutEngine.layout(state("A\n\nB").fontSize(16f).build(), font());
        b = onlyQuadOfMainPass(passes, 1);
        float s16 = 16f / 15f;
        assertEquals(200f - s16 * 11f - 2f * s16 * 18f, b.y1(), 1e-4f);
    }

    @Test
    void missingGlyphFallsBackToQuestionMark() {
        List<TextPass> passes = TextLayoutEngine.layout(state("AC").build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());
        GlyphQuad c = quads.get(1);
        assertEquals(0.30f, c.u1());
        assertEquals(113f, c.x1());
    }

    @Test
    void missingGlyphWithoutFallbackIsSkippedAndKeepsPrevChar() {
        FakeGlyphs noFallback = new FakeGlyphs(false).kern(65, 65, -3);
        List<TextPass> passes = TextLayoutEngine.layout(state("ACA").build(), noFallback);
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size(), "C 与 '?' 都缺失时跳过，不产 quad 不推进");
        assertEquals(111f, quads.get(1).x1());
    }

    @Test
    void selectionStartAndEndSwitchColors() {
        int highlight = TextLayoutEngine.packColor(0xFF0000, 1f);
        List<TextPass> passes = TextLayoutEngine.layout(
                state("AB").selection(0, 0).highlightColor(0xFF0000, 1f).build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(highlight, quads.get(0).color());
        assertEquals(0xFFFFFFFF, quads.get(1).color());

        passes = TextLayoutEngine.layout(
                state("AB").selection(0, 1).highlightColor(0xFF0000, 1f).build(), font());
        quads = passes.get(0).quads();
        assertEquals(highlight, quads.get(0).color());
        assertEquals(highlight, quads.get(1).color());
    }

    @Test
    void wordColorsOverrideHighlightWithOwnAlpha() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("ABC")
                        .charSelection(new boolean[]{true, true, false}, new int[]{0, 0, 0})
                        .wordColors(new int[]{0x0000FF}, new float[]{0.5f})
                        .build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        int wordColor = TextLayoutEngine.packColor(0x0000FF, 0.5f);
        assertEquals(0x7F0000FF, wordColor, "alpha 按 (byte)(255*0.5)=127 截断");
        assertEquals(wordColor, quads.get(0).color());
        assertEquals(wordColor, quads.get(1).color());
        assertEquals(0xFFFFFFFF, quads.get(2).color());
    }

    @Test
    void visibleForcesSelectionColoringOff() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("AB").selection(0, 1).highlightColor(0xFF0000, 1f).visible(true).build(), font());
        for (GlyphQuad q : passes.get(0).quads()) {
            assertEquals(0xFFFFFFFF, q.color(), "visible=true 时选区着色关闭（原版 renderText 行为）");
        }
    }

    @Test
    void borderEmitsFourOffsetPassesBeforeMain() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).outlineColor(0x00FF00).borderAlpha(1f).build(), font());
        assertEquals(5, passes.size());
        int borderColor = 0xFF00FF00;
        float[][] offsets = {{1f, 0f}, {-1f, 0f}, {0f, 1f}, {0f, -1f}};
        for (int i = 0; i < 4; i++) {
            List<GlyphQuad> quads = passes.get(i).quads();
            assertEquals(1, quads.size());
            GlyphQuad q = quads.get(0);
            assertEquals(101f + offsets[i][0], q.x1());
            assertEquals(189f + offsets[i][1], q.y1());
            assertEquals(borderColor, q.color(), "边框 pass 用 outlineColor×borderAlpha");
        }
        assertEquals(0xFFFFFFFF, passes.get(4).quads().get(0).color());
    }

    @Test
    void shadowPassUsesOffsetAndShadowAlpha() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A").shadowEnabled(true).shadowOffset(2f, -3f)
                        .outlineColor(0x000000).shadowAlpha(0.5f).build(), font());
        assertEquals(2, passes.size());
        GlyphQuad shadow = passes.get(0).quads().get(0);
        assertEquals(103f, shadow.x1());
        assertEquals(186f, shadow.y1());
        assertEquals(0x7F000000, shadow.color());
        assertEquals(0xFFFFFFFF, passes.get(1).quads().get(0).color());
    }

    @Test
    void borderPassesDoNotApplySelectionColors() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("AB").borderEnabled(true).outlineColor(0x00FF00)
                        .selection(0, 1).highlightColor(0xFF0000, 1f).build(), font());
        for (int i = 0; i < 4; i++) {
            for (GlyphQuad q : passes.get(i).quads()) {
                assertEquals(0xFF00FF00, q.color(), "边框 pass 无选区着色");
            }
        }
    }

    @Test
    void outlineCopiesExpandWithAspectCorrection() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A").outline(2, 0.25f).build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(3, quads.size(), "2 个放大副本 + 1 主 quad");

        float ex1 = 0.25f * 10f / 12f;
        GlyphQuad copy1 = quads.get(0);
        assertEquals(100f + 1f - ex1, copy1.x1(), 1e-4f);
        assertEquals(200f - 11f - 0.25f, copy1.y1(), 1e-4f);
        assertEquals(100f + 1f - ex1, copy1.x2(), 1e-4f);
        assertEquals(200f - 12f - 11f + 2f * 0.25f, copy1.y2(), 1e-4f);
        assertEquals(100f + 1f + 10f + 2f * ex1, copy1.x3(), 1e-4f);
        assertEquals(copy1.u1(), quads.get(2).u1());
        assertEquals(copy1.v2(), quads.get(2).v2());

        float ex2 = 0.5f * 10f / 12f;
        GlyphQuad copy2 = quads.get(1);
        assertEquals(100f + 1f - ex2, copy2.x1(), 1e-4f);
        assertEquals(200f - 11f - 0.5f, copy2.y1(), 1e-4f);
    }

    @Test
    void compactFontRepeatsEveryPassThreeTimes() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A").compactFont(true).build(), font());
        assertEquals(3, passes.size());
        GlyphQuad first = passes.get(0).quads().get(0);
        for (TextPass pass : passes) {
            assertEquals(1, pass.quads().size());
            assertEquals(first, pass.quads().get(0), "三次迭代产出完全相同的几何");
        }
    }

    @Test
    void underlineUsesUnderscoreGlyphStretchedToCurrentGlyphWidth() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("AB").underlineEnabled(true).selection(0, 0).build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(3, quads.size(), "A + 下划线 + B（下划线仅挂选区起点字形）");
        GlyphQuad ul = quads.get(1);
        assertEquals(101f, ul.x1());
        assertEquals(200f + (-2f - (-2f)), ul.y1(), 1e-4f);
        assertEquals(200f + (-2f - 2f - (-2f)), ul.y2(), 1e-4f);
        assertEquals(111f, ul.x3());
        assertEquals(0.40f, ul.u1(), "下划线用 '_' 字形 UV");
    }

    @Test
    void shearOffsetsXByLocalY() {
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A").shear(0.5f).build(), font());
        GlyphQuad a = passes.get(0).quads().get(0);
        assertEquals(100f + 1f + 0.5f * -11f, a.x1(), 1e-4f);
        assertEquals(189f, a.y1());
        assertEquals(100f + 1f + 0.5f * -23f, a.x2(), 1e-4f);
        assertEquals(177f, a.y2());
    }

    @Test
    void bracePlaceholderAdvancesLikeSpaceWithoutQuad() {
        List<TextPass> passes = TextLayoutEngine.layout(state("A{B").build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size(), "零尺寸占位符不发射 quad");
        assertEquals(117f, quads.get(1).x1());
    }
}
