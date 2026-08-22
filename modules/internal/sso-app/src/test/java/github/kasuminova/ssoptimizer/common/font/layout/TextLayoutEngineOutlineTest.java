package github.kasuminova.ssoptimizer.common.font.layout;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TextLayoutEngine} 的 TTF 描边合成分支（设计文档 §4.5）验证：
 * 边框/outline 从多 pass 位图叠加改为单 pass 剪影 quad 垫底 + 填充盖顶，
 * 阴影偏移在屏幕像素空间取整；假 {@link OutlineGlyphProvider} 注入内存槽位度量。
 */
class TextLayoutEngineOutlineTest {

    // 与 TextLayoutEngineTest 相同基准的 A 字形（名义 15 / 行高 18 / scale 1）
    private static final GlyphMetrics GLYPH_A = new GlyphMetrics(1, 12, 11, 10, 12, 0.10f, 0.10f, 0.05f, 0.06f, 7);
    // 描边槽位：1px 描边的并集画布盒 = 填充盒四向外扩 1px（原点 (-1,-1)，尺寸 +2），
    // xAdvance 补偿左溢出（1+12 = 0+13）——与 TtfGlyphProvider.slotMetrics 产物同构
    private static final GlyphMetrics GLYPH_A_STROKED =
            new GlyphMetrics(0, 13, 10, 12, 14, 0.60f, 0.60f, 0.07f, 0.08f, 99);

    /** 假描边合成源：填充/剪影度量分开存，记录描边请求。 */
    private static final class FakeOutlineGlyphs implements OutlineGlyphProvider {
        final Map<Integer, GlyphMetrics> stroked = new HashMap<>();
        final List<float[]> strokeRequests = new ArrayList<>();
        float bucketScale = 1f;
        boolean synthesize = true;

        @Override
        public GlyphMetrics glyph(final int codePoint) {
            return codePoint == 65 ? GLYPH_A : null;
        }

        @Override
        public GlyphMetrics strokedGlyph(final int codePoint, final float strokeWidthLogicalPx) {
            strokeRequests.add(new float[]{codePoint, strokeWidthLogicalPx});
            return stroked.get(codePoint);
        }

        @Override
        public boolean synthesizesOutline() {
            return synthesize;
        }

        @Override
        public float currentBucketScale() {
            return bucketScale;
        }

        @Override
        public Integer kerning(final int prevCodePoint, final int codePoint) {
            return null;
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

    private static FakeOutlineGlyphs font() {
        final FakeOutlineGlyphs font = new FakeOutlineGlyphs();
        font.stroked.put(65, GLYPH_A_STROKED);
        return font;
    }

    private static TextRenderState.Builder state(final String text) {
        return TextRenderState.builder(text).draw(100f, 200f).fontSize(15f);
    }

    @Test
    void borderSynthesizesToSinglePassWithSilhouetteUnderFill() {
        final FakeOutlineGlyphs font = font();
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).outlineColor(0x00FF00).borderAlpha(1f).build(), font);

        assertEquals(1, passes.size(), "描边合成：边框不再产 4 向偏移 pass");
        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size(), "每字形 2 quad：剪影垫底 + 填充盖顶");

        final GlyphQuad silhouette = quads.get(0);
        assertEquals(0xFF00FF00, silhouette.color(), "剪影用描边色×borderAlpha");
        assertEquals(99, silhouette.textureId(), "剪影采样描边图集槽位");
        assertEquals(0.60f, silhouette.u1(), 1e-6f);
        // 剪影盒取描边槽位度量（= 填充盒四向外扩 1px 的并集盒，锚定同一落笔原点）：
        // 填充盒 [101..111]×[177..189] → 剪影 [100..112]×[176..190]
        assertEquals(100f, silhouette.x1(), 1e-4f);
        assertEquals(190f, silhouette.y1(), 1e-4f);
        assertEquals(100f, silhouette.x2(), 1e-4f);
        assertEquals(176f, silhouette.y2(), 1e-4f);
        assertEquals(112f, silhouette.x3(), 1e-4f);
        assertEquals(112f, silhouette.x4(), 1e-4f);

        final GlyphQuad fill = quads.get(1);
        assertEquals(0xFFFFFFFF, fill.color());
        assertEquals(7, fill.textureId(), "填充 quad 携带填充槽位纹理 id");
        assertEquals(101f, fill.x1(), 1e-4f);
        assertEquals(189f, fill.y1(), 1e-4f);

        assertEquals(1, font.strokeRequests.size());
        assertEquals(1.0f, font.strokeRequests.get(0)[1], 1e-6f, "边框剪影宽度恒 1px 逻辑宽");
    }

    @Test
    void outlineCopiesSynthesizeSilhouetteWithGlyphColor() {
        final FakeOutlineGlyphs font = font();
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").outline(2, 0.25f).build(), font);

        assertEquals(1, passes.size());
        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size(), "outline 合成：不再有 N 个放大副本，只 1 剪影 + 1 填充");
        assertEquals(0xFFFFFFFF, quads.get(0).color(), "仅 outline 时剪影跟随字形当前色（原版放大副本语义）");
        assertEquals(0.5f, font.strokeRequests.get(0)[1], 1e-6f, "剪影宽度 = shadowCopies × shadowScale");
    }

    @Test
    void borderAndCopiesCombineIntoSingleSilhouetteWithMaxWidth() {
        final FakeOutlineGlyphs font = font();
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).outlineColor(0x00FF00).outline(3, 1.0f).build(), font);

        assertEquals(1, passes.size());
        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());
        assertEquals(0xFF00FF00, quads.get(0).color(), "合并剪影用描边色");
        assertEquals(3.0f, font.strokeRequests.get(0)[1], 1e-6f, "宽度取 max(1px 边框, copies×scale)");
    }

    @Test
    void shadowOffsetSnapsToScreenPixels() {
        final FakeOutlineGlyphs font = font();
        font.bucketScale = 1.5f;
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").shadowEnabled(true).shadowOffset(1.3f, -0.7f)
                        .outlineColor(0x000000).shadowAlpha(0.5f).build(), font);

        assertEquals(2, passes.size(), "阴影偏移黑副本维持独立 pass");
        final GlyphQuad shadow = passes.get(0).quads().get(0);
        // off' = round(off × 1.5) / 1.5：1.3 → 2/1.5；-0.7 → -1/1.5
        assertEquals(101f + 2f / 1.5f, shadow.x1(), 1e-4f);
        assertEquals(189f - 1f / 1.5f, shadow.y1(), 1e-4f);
        assertEquals(0x7F000000, shadow.color());
        assertTrue(font.strokeRequests.isEmpty(), "纯阴影（无边框/outline）不请求描边剪影");
    }

    @Test
    void shadowAndBorderStayMutuallyExclusive() {
        final FakeOutlineGlyphs font = font();
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).shadowEnabled(true).shadowOffset(2f, 2f).build(), font);

        assertEquals(1, passes.size(), "原版 border 与 shadow 互斥（else-if），合成路径保持");
    }

    @Test
    void bitmapStyleMultiPassWhenProviderDeclinesSynthesis() {
        final FakeOutlineGlyphs font = font();
        font.stroked.clear();
        font.synthesize = false;
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).outlineColor(0x00FF00).build(), font);

        assertEquals(5, passes.size(), "synthesizesOutline=false 回退原版 4 pass 边框 + 主 pass");
    }

    @Test
    void strokedGlyphMissingSkipsSilhouetteButKeepsFill() {
        final FakeOutlineGlyphs font = font();
        font.stroked.clear();
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").borderEnabled(true).outlineColor(0x00FF00).build(), font);

        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(1, quads.size(), "剪影栅格化失败时只发填充 quad（缺失语义同 '?' 回退的宽松形态）");
        assertEquals(7, quads.get(0).textureId());
    }

    @Test
    void silhouetteQuadsGroupBeforeFillsAndQueriesAreDeduplicated() {
        final Map<Integer, GlyphMetrics> fills = Map.of(
                65, GLYPH_A,
                66, new GlyphMetrics(0, 11, 11, 9, 12, 0.20f, 0.20f, 0.05f, 0.06f, 8));
        final Map<Integer, GlyphMetrics> strokes = Map.of(
                65, GLYPH_A_STROKED,
                66, new GlyphMetrics(0, 13, 10, 12, 14, 0.70f, 0.70f, 0.07f, 0.08f, 100));
        final List<Integer> glyphQueries = new ArrayList<>();
        final List<Integer> strokedQueries = new ArrayList<>();
        final OutlineGlyphProvider font = new OutlineGlyphProvider() {
            @Override
            public GlyphMetrics glyph(final int codePoint) {
                glyphQueries.add(codePoint);
                return fills.get(codePoint);
            }

            @Override
            public GlyphMetrics strokedGlyph(final int codePoint, final float strokeWidthLogicalPx) {
                strokedQueries.add(codePoint);
                return strokes.get(codePoint);
            }

            @Override
            public boolean synthesizesOutline() {
                return true;
            }

            @Override
            public float currentBucketScale() {
                return 1f;
            }

            @Override
            public Integer kerning(final int prevCodePoint, final int codePoint) {
                return null;
            }

            @Override
            public int nominalFontSize() {
                return 15;
            }

            @Override
            public int lineHeight() {
                return 18;
            }
        };

        final List<TextPass> passes = TextLayoutEngine.layout(
                state("ABA").borderEnabled(true).outlineColor(0x00FF00).borderAlpha(1f).build(), font);

        assertEquals(1, passes.size());
        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(6, quads.size(), "3 字形 × (剪影 + 填充)");
        assertEquals(List.of(99, 100, 99, 7, 8, 7),
                quads.stream().map(GlyphQuad::textureId).toList(),
                "剪影组（描边纹理 99/100）集中在 pass 头部、填充组（7/8）随后——"
                        + "发射层按 textureId 切段时不再逐字形交错（2N 段 → 2 组）");
        assertEquals(List.of(65, 66), glyphQueries, "逐码点缓存：同码点同 render 只穿透查询一次");
        assertEquals(List.of(65, 66), strokedQueries, "描边查询同样按码点去重");
    }

    @Test
    void splitAlphasAreRecombinedForSingleLayerStrokeSynthesis() {
        final FakeOutlineGlyphs font = font();
        // 战斗浮字场景（FloatingText：border+shadow+outlinePasses(3)，alpha=0.85）：
        // 原版 setAlpha 按 copies+1=4 层叠画预分解 textAlpha ≈ 0.3777、
        // borderAlpha = textAlpha² ≈ 0.1426；合成路径剪影/填充各一层，必须重聚合
        final float splitText = 1f - (float) Math.pow(1.0 - 0.85, 1.0 / 4.0);
        final float splitBorder = splitText * splitText;
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").textColor(0xFFFFFF, splitText)
                        .borderEnabled(true).outlineColor(0x000000).borderAlpha(splitBorder)
                        .outline(3, 0.125f).build(), font);

        assertEquals(1, passes.size());
        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());

        final int silhouetteAlpha = quads.get(0).color() >>> 24;
        // 边框剪影按 4 pass × 4 层 = 16 层累计：1-(1-0.1426)^16 ≈ 0.915 → 字节 233
        assertTrue(silhouetteAlpha >= 230 && silhouetteAlpha <= 236,
                "边框剪影 alpha 需按 16 层重聚合（≈233），实际=" + silhouetteAlpha);
        assertEquals(0x000000, quads.get(0).color() & 0xFFFFFF, "剪影仍是描边黑色");

        final int fillAlpha = quads.get(1).color() >>> 24;
        // 填充按 copies+1=4 层累计回 0.85 → 字节 ≈216（截断字节 96 重聚合）
        assertTrue(fillAlpha >= 213 && fillAlpha <= 219,
                "填充 alpha 需重聚合回 0.85（≈216），预分解值仅 96（实机半透明症状），实际=" + fillAlpha);
    }

    @Test
    void followColorSilhouetteCountsAsOneLayerOnFillRecombination() {
        final FakeOutlineGlyphs font = font();
        // 仅 outline（copies=2、无边框）：alpha=0.85 的预分解 textAlpha ≈ 0.4687；
        // 随字色剪影自身充当一层，填充只补 copies=2 层
        final float splitText = 1f - (float) Math.pow(1.0 - 0.85, 1.0 / 3.0);
        final List<TextPass> passes = TextLayoutEngine.layout(
                state("A").textColor(0xFFFFFF, splitText).outline(2, 0.25f).build(), font);

        final List<GlyphQuad> quads = passes.get(0).quads();
        assertEquals(2, quads.size());
        final int silhouetteAlpha = quads.get(0).color() >>> 24;
        assertEquals((int) (255f * splitText), silhouetteAlpha,
                "随字色剪影保持预分解单层 alpha（充当叠画一层）");
        final int fillAlpha = quads.get(1).color() >>> 24;
        // 填充补 2 层：1-(1-119/255)² ≈ 0.7156 → 字节 182；与剪影累计 ≈ 0.85
        assertTrue(fillAlpha >= 179 && fillAlpha <= 185,
                "填充按 copies=2 层重聚合（≈182），实际=" + fillAlpha);
    }
}
