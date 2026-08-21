package github.kasuminova.ssoptimizer.common.font.layout;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本布局引擎：完整复刻原版 {@code BitmapFontRenderer} 私有渲染链
 * （render → drawText → renderText/drawGlyphs → drawGlyph/drawUnderline）的
 * 逐字形几何、颜色与 pass 语义，产出与 GL 无关的 {@link TextPass} 序列。
 * <p>
 * 动机：原版渲染在最终 quad 发射点已丢失 codepoint 与颜色语义，无法在其下重写；
 * 本引擎在保留全部文本语义的层级重建布局结果，发射交给
 * {@code TextStreamEmitter} 走渲染线程顶点流。与原版的行为对应关系（逆向自
 * named 源码，行号以 0.98a-RC8 为准）：
 * <ul>
 *   <li>pass 结构对应 render() L637-666：边框 4 向 ±1px（outlineColor×borderAlpha）
 *       → 否则阴影 1 pass（shadowOffset）→ 主 pass（textColor×textAlpha）；</li>
 *   <li>主循环对应 renderText L874-972 内联路径：逐字符 penX 累积
 *       （xOffset → kerning → xAdvance）、'\n' 换行（含空行 fontSize==18/15 时
 *       固定 -10 的原版怪癖）、缺失字形回退码点 63（仍缺失则跳过且不更新前驱字符）；</li>
 *   <li>选区/高亮/词色对应 isSelectionStart/isSelectionEnd（L611/L620 的边界语义）：
 *       仅主 pass 着色（且 visible=true 时强制关闭），颜色切换烘焙进逐 quad 颜色；</li>
 *   <li>描边放大副本对应 drawGlyph L974-1010 的 shadowCopies 循环（先于主 quad 发射，
 *       横纵扩张按宽高比不对称缩放）；</li>
 *   <li>下划线对应 drawUnderline L1022-1033：用码点 95 字形在 y-2 处拉伸到当前字形宽度，
 *       且仅在选区起点字形发射（原版如此，各 pass 都会发射）；</li>
 *   <li>compact 字体（isCompactFont）每 pass 重复 3 次；shear 对应 drawGlyphs 路径的
 *       逐行 translate+multMatrix，折算为 x += shear × 行内局部 y；</li>
 *   <li>display list 缓存不复制——新链路逐帧布局，无列表语义。</li>
 * </ul>
 * 坐标系：输出顶点为绝对坐标（drawX/drawY 与 pass 偏移已烘焙），
 * y 向下为正（与原版顶点值一致，外部模型矩阵由调用方维持）。
 */
public final class TextLayoutEngine {

    /** 缺失字形回退码点（'?'），原版硬编码 glyphs[63]。 */
    private static final int FALLBACK_GLYPH = 63;
    /** 下划线字形码点（'_'），原版硬编码 glyphs[95]。 */
    private static final int UNDERLINE_GLYPH = 95;

    private TextLayoutEngine() {
    }

    /**
     * 对一次 render() 调用做完整布局。
     *
     * @param s       渲染状态快照
     * @param glyphs  字形度量来源
     * @return 有序 pass 序列（边框/阴影在前，主 pass 在最后；compact 字体每逻辑 pass 展开为 3 个）
     */
    public static List<TextPass> layout(final TextRenderState s, final GlyphProvider glyphs) {
        final float scale = s.fontSize() / glyphs.nominalFontSize();
        final int iterations = s.compactFont() ? 3 : 1;

        final List<TextPass> passes = new ArrayList<>();
        if (s.borderEnabled()) {
            final int borderColor = packColor(s.outlineColorRgb(), s.borderAlpha());
            buildPass(passes, s, glyphs, scale, 1f, 0f, borderColor, false, iterations);
            buildPass(passes, s, glyphs, scale, -1f, 0f, borderColor, false, iterations);
            buildPass(passes, s, glyphs, scale, 0f, 1f, borderColor, false, iterations);
            buildPass(passes, s, glyphs, scale, 0f, -1f, borderColor, false, iterations);
        } else if (s.shadowEnabled()) {
            buildPass(passes, s, glyphs, scale, s.shadowOffsetX(), s.shadowOffsetY(),
                    packColor(s.outlineColorRgb(), s.shadowAlpha()), false, iterations);
        }
        buildPass(passes, s, glyphs, scale, 0f, 0f,
                packColor(s.textColorRgb(), s.textAlpha()), true, iterations);
        return passes;
    }

    /**
     * 生成一个逻辑 pass（compact 字体展开为 iterations 个 TextPass）。
     *
     * @param offX/offY          pass 平移（边框 ±1px / 阴影偏移 / 主 pass 0）
     * @param baseColor          pass 起始颜色（ARGB）
     * @param selectionColoring  是否启用选区/高亮着色（仅主 pass，且 visible 时强制关闭）
     */
    private static void buildPass(
            final List<TextPass> out,
            final TextRenderState s,
            final GlyphProvider g,
            final float scale,
            final float offX,
            final float offY,
            final int baseColor,
            final boolean selectionColoring,
            final int iterations) {
        final boolean coloring = selectionColoring && !s.visible();
        final int textColor = packColor(s.textColorRgb(), s.textAlpha());
        final String text = s.text();

        for (int iter = 0; iter < iterations; iter++) {
            final List<GlyphQuad> quads = new ArrayList<>();
            float penX = 0f;
            float lineY = 0f;
            int prev = -1;
            int currentColor = baseColor;

            for (int i = 0; i < text.length(); i++) {
                final char c = text.charAt(i);
                if (c == '\n') {
                    // 原版怪癖：空行（penX==0）且前驱也是 '\n' 时，18/15 号字固定回退 10px
                    if (penX == 0f && s.fontSize() == 18f && prev == 10) {
                        lineY -= 10f;
                    } else if (penX == 0f && s.fontSize() == 15f && prev == 10) {
                        lineY -= 10f;
                    } else {
                        lineY -= scale * g.lineHeight();
                    }
                    penX = 0f;
                    if (coloring && isSelectionEnd(s, i)) {
                        currentColor = textColor;
                    }
                    prev = 10;
                    continue;
                }

                GlyphMetrics gm = g.glyph(c);
                if (gm == null) {
                    gm = g.glyph(FALLBACK_GLYPH);
                    if (gm == null) {
                        // 原版行为：连 '?' 都没有时跳过该字符，不推进 penX、不更新前驱
                        if (coloring && isSelectionEnd(s, i)) {
                            currentColor = textColor;
                        }
                        continue;
                    }
                }

                penX += scale * gm.xOffset();
                if (prev != -1) {
                    final Integer kern = g.kerning(prev, c);
                    if (kern != null) {
                        penX += scale * kern;
                    }
                }

                if (coloring && isSelectionStart(s, i)) {
                    currentColor = resolveHighlight(s, i);
                }

                emitGlyph(quads, s, gm, scale, penX, lineY, currentColor, offX, offY);
                if (s.underlineEnabled() && isSelectionStart(s, i)) {
                    emitUnderline(quads, s, g, gm, scale, penX, lineY, currentColor, offX, offY);
                }

                penX += scale * gm.xAdvance();
                prev = c;
                if (coloring && isSelectionEnd(s, i)) {
                    currentColor = textColor;
                }
            }
            out.add(new TextPass(quads));
        }
    }

    /** 单字形 quad：先描边放大副本（shadowCopies 个），再主 quad；坐标见 drawGlyph L974-1010。 */
    private static void emitGlyph(
            final List<GlyphQuad> quads,
            final TextRenderState s,
            final GlyphMetrics gm,
            final float scale,
            final float penX,
            final float lineY,
            final int color,
            final float offX,
            final float offY) {
        final float sb = scale * gm.bearingY();
        final float sh = scale * gm.height();
        final float sw = scale * gm.width();
        final float u0 = gm.texX();
        final float v0 = gm.texY();
        final float u1 = gm.texX() + gm.texWidth();
        final float v1 = gm.texY() + gm.texHeight();

        if (s.shadowCopies() > 0) {
            final float w = gm.width();
            final float h = gm.height();
            for (int k = 1; k <= s.shadowCopies(); k++) {
                final float expand = k * s.shadowScale();
                float ex = expand;
                float ey = expand;
                if (w > h) {
                    ey *= h / w;
                }
                if (h > w) {
                    ex *= w / h;
                }
                quads.add(quad(s, offX, offY, lineY, color,
                        penX - ex, -sb - ey, u0, v1,
                        penX - ex, -sh - sb + ey * 2f, u0, v0,
                        penX + sw + ex * 2f, -sh - sb + ey * 2f, u1, v0,
                        penX + sw + ex * 2f, -sb - ey, u1, v1));
            }
        }

        quads.add(quad(s, offX, offY, lineY, color,
                penX, -sb, u0, v1,
                penX, -sh - sb, u0, v0,
                penX + sw, -sh - sb, u1, v0,
                penX + sw, -sb, u1, v1));
    }

    /** 下划线 quad：码点 95 字形在 y-2 处拉伸到当前字形宽度（drawUnderline L1022-1033）。 */
    private static void emitUnderline(
            final List<GlyphQuad> quads,
            final TextRenderState s,
            final GlyphProvider g,
            final GlyphMetrics current,
            final float scale,
            final float penX,
            final float lineY,
            final int color,
            final float offX,
            final float offY) {
        final GlyphMetrics um = g.glyph(UNDERLINE_GLYPH);
        if (um == null) {
            throw new IllegalStateException("下划线启用但字体缺失 '_'（码点 95）字形，原版在此会 NPE");
        }
        final float sb = scale * um.bearingY();
        final float sh = scale * um.height();
        final float sw = scale * current.width();
        final float u0 = um.texX();
        final float v0 = um.texY();
        final float u1 = um.texX() + um.texWidth();
        final float v1 = um.texY() + um.texHeight();
        // 原版把 y-2 作为该 quad 的基准：-2 属于局部坐标（drawGlyphs 路径下同样被 shear 矩阵
        // 作用），因此并入各顶点 vyLocal 而非 lineY
        quads.add(quad(s, offX, offY, lineY, color,
                penX, -2f - sb, u0, v1,
                penX, -2f - sh - sb, u0, v0,
                penX + sw, -2f - sh - sb, u1, v0,
                penX + sw, -2f - sb, u1, v1));
    }

    /**
     * 组装绝对坐标 quad：局部 y（vyLocal，相对行基线）经 shear 折算到 x，
     * 行偏移 lineY 在 shear 之后叠加（等价原版 drawGlyphs 的 translate(0,lineY)+multMatrix 顺序）。
     */
    private static GlyphQuad quad(
            final TextRenderState s,
            final float offX,
            final float offY,
            final float lineY,
            final int color,
            final float x1, final float y1, final float u1, final float v1,
            final float x2, final float y2, final float u2, final float v2,
            final float x3, final float y3, final float u3, final float v3,
            final float x4, final float y4, final float u4, final float v4) {
        final float baseX = s.drawX() + offX;
        final float baseY = s.drawY() + offY + lineY;
        final float shear = s.shear();
        return new GlyphQuad(
                baseX + x1 + shear * y1, baseY + y1, u1, v1,
                baseX + x2 + shear * y2, baseY + y2, u2, v2,
                baseX + x3 + shear * y3, baseY + y3, u3, v3,
                baseX + x4 + shear * y4, baseY + y4, u4, v4,
                color);
    }

    /** 选区起点判定，逐字复刻原版 isSelectionStart（含 charSelectionFlags 长度越界返回 false 的写法）。 */
    private static boolean isSelectionStart(final TextRenderState s, final int i) {
        if (i == s.selectionStart()) {
            return true;
        }
        final boolean[] flags = s.charSelectionFlags();
        if (flags == null || flags.length <= i) {
            return false;
        }
        return flags[i] && (i == 0 || !flags[i - 1]);
    }

    /** 选区终点判定，逐字复刻原版 isSelectionEnd。 */
    private static boolean isSelectionEnd(final TextRenderState s, final int i) {
        if (i == s.selectionEnd()) {
            return true;
        }
        final boolean[] flags = s.charSelectionFlags();
        if (flags == null || flags.length <= i) {
            return false;
        }
        return flags[i] && (i >= flags.length - 1 || !flags[i + 1]);
    }

    /**
     * 选区起点颜色解析：wordColors 命中时用词色，否则 highlightColor。
     * 原版在 colorAlphas 为 null 时会 NPE（setWordColors 后未走 setAlpha 的病态调用序），
     * 此处对 colorAlphas 越界/缺失退回 highlightAlpha，避免渲染期崩溃。
     */
    private static int resolveHighlight(final TextRenderState s, final int i) {
        int rgb = s.highlightColorRgb();
        float alpha = s.highlightAlpha();
        final int[] wordColors = s.wordColorsRgb();
        final int[] wordIndexes = s.charWordIndexes();
        if (wordColors != null && wordIndexes != null) {
            final int wordIndex = wordIndexes[i];
            if (wordColors.length > wordIndex) {
                rgb = wordColors[wordIndex];
                final float[] colorAlphas = s.colorAlphas();
                if (colorAlphas != null && colorAlphas.length > wordIndex) {
                    alpha = colorAlphas[wordIndex];
                }
            }
        }
        return packColor(rgb, alpha);
    }

    /**
     * 打包 ARGB：alpha 按原版 (byte)(255f * alpha) 语义截断后取低 8 位。
     */
    static int packColor(final int rgb, final float alpha) {
        final int a = ((byte) (255.0f * alpha)) & 0xFF;
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
