package github.kasuminova.ssoptimizer.common.font.layout;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextLayoutEngine} 的原版语义复刻验证。
 * <p>
 * 基准：0.98a-RC8 named 源码 BitmapFontRenderer 的 render/drawText/renderText/drawGlyph/
 * drawUnderline 控制流（见引擎 javadoc 的对应表）；期望值全部按原版公式手工计算，
 * fixture 字形度量经假 {@link GlyphProvider} 注入，不依赖游戏类。
 */
class TextLayoutEngineTest {

    // ── fixture：固定度量的小字体（名义 15 / 行高 18）─────────────────────
    private static final GlyphMetrics GLYPH_A = new GlyphMetrics(1, 12, 11, 10, 12, 0.10f, 0.10f, 0.05f, 0.06f);
    private static final GlyphMetrics GLYPH_B = new GlyphMetrics(0, 11, 11, 9, 12, 0.20f, 0.10f, 0.05f, 0.06f);
    private static final GlyphMetrics GLYPH_Q = new GlyphMetrics(0, 8, 10, 7, 11, 0.30f, 0.10f, 0.05f, 0.06f);
    private static final GlyphMetrics GLYPH_UL = new GlyphMetrics(0, 10, -2, 8, 2, 0.40f, 0.10f, 0.05f, 0.06f);
    private static final GlyphMetrics GLYPH_BRACE = new GlyphMetrics(0, 4, 0, 0, 0, 0.50f, 0.10f, 0f, 0f);

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

    // ── 基础几何 ────────────────────────────────────────────────────────

    @Test
    void basicLayoutMatchesVanillaGeometry() {
        List<TextPass> passes = TextLayoutEngine.layout(state("AB").build(), font());
        assertEquals(1, passes.size(), "无边框/阴影/compact 时只有主 pass");
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());

        // A：penX = xOffset 1；顶点按 drawGlyph 公式（scale=1）
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

        // B：penX = 12+1(A advance 累计) ... = 13 + xOffset 0 + kerning(-2) = 11
        GlyphQuad b = quads.get(1);
        assertEquals(111f, b.x1());
        assertEquals(120f, b.x3());
        assertEquals(189f, b.y1());
    }

    @Test
    void newlineQuirkFixedTenPixelsAtSize18And15() {
        // 18 号字双换行：第一行非空 → -1.2*18；第二空行且 size==18 → 固定 -10
        List<TextPass> passes = TextLayoutEngine.layout(
                state("A\n\nB").fontSize(18f).build(), font());
        GlyphQuad b = onlyQuadOfMainPass(passes, 1);
        float scale = 18f / 15f;
        float lineY = -scale * 18f - 10f;
        assertEquals(200f - scale * 11f + lineY, b.y1(), 1e-4f);

        // 15 号字双换行：-18 -10
        passes = TextLayoutEngine.layout(state("A\n\nB").fontSize(15f).build(), font());
        b = onlyQuadOfMainPass(passes, 1);
        assertEquals(200f - 11f - 18f - 10f, b.y1(), 1e-4f);

        // 16 号字无怪癖：两行都按 scale*lineHeight
        passes = TextLayoutEngine.layout(state("A\n\nB").fontSize(16f).build(), font());
        b = onlyQuadOfMainPass(passes, 1);
        float s16 = 16f / 15f;
        assertEquals(200f - s16 * 11f - 2f * s16 * 18f, b.y1(), 1e-4f);
    }

    // ── 缺失字形 ────────────────────────────────────────────────────────

    @Test
    void missingGlyphFallsBackToQuestionMark() {
        List<TextPass> passes = TextLayoutEngine.layout(state("AC").build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());
        // C 缺失 → 用 '?'（63）的度量与 UV
        GlyphQuad c = quads.get(1);
        assertEquals(0.30f, c.u1());
        // penX = A(13) + xOffset 0 = 13（无 kerning 对 65→67）
        assertEquals(113f, c.x1());
    }

    @Test
    void missingGlyphWithoutFallbackIsSkippedAndKeepsPrevChar() {
        FakeGlyphs noFallback = new FakeGlyphs(false).kern(65, 65, -3);
        List<TextPass> passes = TextLayoutEngine.layout(state("ACA").build(), noFallback);
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size(), "C 与 '?' 都缺失时跳过，不产 quad 不推进");
        // 第二个 A：prev 仍是 'A'（跳过的 C 不更新前驱）→ kerning(65,65)=-3 生效
        // penX = 13(A) + xOffset 1 + kern(-3) = 11
        assertEquals(111f, quads.get(1).x1());
    }

    // ── 选区 / 高亮 / 词色 ──────────────────────────────────────────────

    @Test
    void selectionStartAndEndSwitchColors() {
        int highlight = TextLayoutEngine.packColor(0xFF0000, 1f);
        // 选区 [0,0]：A 高亮，isSelectionEnd(0) 后 B 回文本色
        List<TextPass> passes = TextLayoutEngine.layout(
                state("AB").selection(0, 0).highlightColor(0xFF0000, 1f).build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(highlight, quads.get(0).color());
        assertEquals(0xFFFFFFFF, quads.get(1).color());

        // 选区 [0,1]：两个都高亮（终点颜色切换在该字形 quad 之后）
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

    // ── 边框 / 阴影 pass ────────────────────────────────────────────────

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

    // ── 描边放大副本（shadowCopies）─────────────────────────────────────

    @Test
    void outlineCopiesExpandWithAspectCorrection() {
        // w=10 < h=12 → 横向扩张乘 w/h
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
        // 副本 UV 与主 quad 相同（同一张字形位图放大采样）
        assertEquals(copy1.u1(), quads.get(2).u1());
        assertEquals(copy1.v2(), quads.get(2).v2());

        float ex2 = 0.5f * 10f / 12f;
        GlyphQuad copy2 = quads.get(1);
        assertEquals(100f + 1f - ex2, copy2.x1(), 1e-4f);
        assertEquals(200f - 11f - 0.5f, copy2.y1(), 1e-4f);
    }

    // ── compact 字体 / 下划线 / shear ───────────────────────────────────

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
        // '_' 度量：bearingY=-2 height=2，基准 y 局部 -2；宽拉伸到 A.width=10
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
        // x' = x + shear*yLocal；y 不变
        assertEquals(100f + 1f + 0.5f * -11f, a.x1(), 1e-4f);
        assertEquals(189f, a.y1());
        assertEquals(100f + 1f + 0.5f * -23f, a.x2(), 1e-4f);
        assertEquals(177f, a.y2());
    }

    // ── 特殊字形（占位符语义已烘焙在 fnt 度量中）─────────────────────────

    @Test
    void bracePlaceholderAdvancesLikeSpaceWithDegenerateQuad() {
        List<TextPass> passes = TextLayoutEngine.layout(state("A{B").build(), font());
        List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(3, quads.size(), "原版对零尺寸占位符仍发射退化 quad");
        GlyphQuad brace = quads.get(1);
        assertEquals(brace.x1(), brace.x3(), "零宽字形 quad 退化");
        // '{' 贡献 xAdvance 4：B 的 penX = 13 + 4 + 0 + kern(123,66)=null → 17
        assertEquals(117f, quads.get(2).x1());
    }
}
