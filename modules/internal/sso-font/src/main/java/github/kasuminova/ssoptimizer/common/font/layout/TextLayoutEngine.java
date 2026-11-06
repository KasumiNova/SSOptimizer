package github.kasuminova.ssoptimizer.common.font.layout;

import github.kasuminova.ssoptimizer.common.render.engine.TextScaleBuckets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TextLayoutEngine {

    private static final int FALLBACK_GLYPH = 63;
    private static final int UNDERLINE_GLYPH = 95;
    private static final float BORDER_STROKE_WIDTH = 1f;

    static volatile boolean strokeSynthesizeEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.stroke.synthesize", "true"));

    static volatile boolean shadowPassEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.shadow", "true"));

    static volatile boolean borderPassEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.border", "true"));

    private TextLayoutEngine() {
    }

    public static List<TextPass> layout(final TextRenderState s, final GlyphProvider glyphs) {
        final GlyphProvider sized = glyphs.forScale(s.fontSize() / glyphs.nominalFontSize());
        final float scale = s.fontSize() / sized.nominalFontSize();
        final int iterations = s.compactFont() ? 3 : 1;
        final OutlineGlyphProvider outline = strokeSynthesizeEnabled
                && sized instanceof OutlineGlyphProvider candidate
                && candidate.synthesizesOutline()
                ? candidate : null;
        // 缓存仅在单次布局内存活，避免图集淘汰或上下文重建后的失效处理。
        final GlyphSourceCache cache = new GlyphSourceCache(sized, outline);

        final List<TextPass> passes = new ArrayList<>();
        if (outline != null) {
            layoutWithStrokeSynthesis(passes, s, cache, cache, scale, iterations);
            return passes;
        }
        if (s.borderEnabled() && borderPassEnabled) {
            final int borderColor = packColor(s.outlineColorRgb(), s.borderAlpha());
            buildPass(passes, s, cache, scale, 1f, 0f, borderColor, false, iterations, null, 0f, null);
            buildPass(passes, s, cache, scale, -1f, 0f, borderColor, false, iterations, null, 0f, null);
            buildPass(passes, s, cache, scale, 0f, 1f, borderColor, false, iterations, null, 0f, null);
            buildPass(passes, s, cache, scale, 0f, -1f, borderColor, false, iterations, null, 0f, null);
        } else if (s.shadowEnabled() && shadowPassEnabled) {
            buildPass(passes, s, cache, scale, s.shadowOffsetX(), s.shadowOffsetY(),
                    packColor(s.outlineColorRgb(), s.shadowAlpha()), false, iterations, null, 0f, null);
        }
        buildPass(passes, s, cache, scale, 0f, 0f,
                packColor(s.textColorRgb(), s.textAlpha()), true, iterations, null, 0f, null);
        return passes;
    }

    private static void layoutWithStrokeSynthesis(
            final List<TextPass> passes,
            final TextRenderState s,
            final GlyphProvider sized,
            final OutlineGlyphProvider outline,
            final float scale,
            final int iterations) {
        // 原版边框与阴影互斥，剪影合成路径保持该语义。
        final boolean border = s.borderEnabled() && borderPassEnabled;
        if (!border && s.shadowEnabled() && shadowPassEnabled) {
            final float bucketScale = outline.currentBucketScale();
            final float snapX = Math.round(s.shadowOffsetX() * bucketScale) / bucketScale;
            final float snapY = Math.round(s.shadowOffsetY() * bucketScale) / bucketScale;
            buildPass(passes, s, sized, scale, snapX, snapY,
                    packColor(s.outlineColorRgb(), s.shadowAlpha()), false, iterations, null, 0f, null);
        }

        // 单剪影需按原版四向边框与放大副本的总层数重聚合 alpha。
        final float strokeWidth;
        final Integer strokeColor;
        if (border && s.shadowCopies() > 0) {
            strokeWidth = Math.max(BORDER_STROKE_WIDTH, s.shadowCopies() * s.shadowScale());
            strokeColor = packColor(s.outlineColorRgb(),
                    recombineLayers(s.borderAlpha(), 4 * (s.shadowCopies() + 1)));
        } else if (border) {
            strokeWidth = BORDER_STROKE_WIDTH;
            strokeColor = packColor(s.outlineColorRgb(),
                    recombineLayers(s.borderAlpha(), 4 * (s.shadowCopies() + 1)));
        } else if (s.shadowCopies() > 0 && borderPassEnabled) {
            strokeWidth = s.shadowCopies() * s.shadowScale();
            strokeColor = null;
        } else {
            strokeWidth = 0f;
            strokeColor = null;
        }
        buildPass(passes, s, sized, scale, 0f, 0f,
                packColor(s.textColorRgb(), s.textAlpha()), true, iterations,
                outline, strokeWidth, strokeColor);
    }

    private static float recombineLayers(final float perLayerAlpha, final int layers) {
        if (layers <= 1) {
            return perLayerAlpha;
        }
        return 1f - (float) Math.pow(1f - perLayerAlpha, layers);
    }

    private static int recombineSplitAlpha(final int argb, final int layers) {
        if (layers <= 1) {
            return argb;
        }
        final float perLayer = ((argb >>> 24) & 0xFF) / 255f;
        final int combined = Math.min(255, Math.round((1f - (float) Math.pow(1f - perLayer, layers)) * 255f));
        return (combined << 24) | (argb & 0xFFFFFF);
    }

    private static void buildPass(
            final List<TextPass> out,
            final TextRenderState s,
            final GlyphProvider g,
            final float scale,
            final float offX,
            final float offY,
            final int baseColor,
            final boolean selectionColoring,
            final int iterations,
            final OutlineGlyphProvider outline,
            final float strokeWidth,
            final Integer strokeColor) {
        final boolean coloring = selectionColoring && !s.visible();
        final int textColor = packColor(s.textColorRgb(), s.textAlpha());
        final String text = s.text();

        for (int iter = 0; iter < iterations; iter++) {
            final List<GlyphQuad> fills = new ArrayList<>();
            final List<GlyphQuad> silhouettes =
                    outline != null && strokeWidth > 0f ? new ArrayList<>() : null;
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

                emitGlyph(fills, silhouettes, s, outline, strokeWidth, strokeColor, c, gm, scale, penX, lineY, currentColor, offX, offY);
                if (s.underlineEnabled() && isSelectionStart(s, i)) {
                    emitUnderline(fills, s, g, gm, scale, penX, lineY, currentColor, offX, offY);
                }

                penX += scale * gm.xAdvance();
                prev = c;
                if (coloring && isSelectionEnd(s, i)) {
                    currentColor = textColor;
                }
            }
            final List<GlyphQuad> quads;
            if (silhouettes != null) {
                silhouettes.addAll(fills);
                quads = silhouettes;
            } else {
                quads = fills;
            }
            out.add(new TextPass(quads));
        }
    }

    private static void emitGlyph(
            final List<GlyphQuad> quads,
            final List<GlyphQuad> silhouetteSink,
            final TextRenderState s,
            final OutlineGlyphProvider outline,
            final float strokeWidth,
            final Integer strokeColor,
            final int codePoint,
            final GlyphMetrics gm,
            final float scale,
            final float penX,
            final float lineY,
            final int color,
            final float offX,
            final float offY) {
        if (gm.width() == 0 && gm.height() == 0) {
            return;
        }
        final float sb = scale * gm.bearingY();
        final float sh = scale * gm.height();
        final float sw = scale * gm.width();

        // 仅在无剪切时吸附设备像素网格，避免剪切坐标与逐字采样相位破坏网格对齐。
        final float devGrid = outline != null && s.shear() == 0f
                ? outline.currentBucketScale()
                : 0f;
        final float baseX = s.drawX() + offX;
        final float baseY = s.drawY() + offY + lineY;

        final boolean strokeSynthesis = outline != null && strokeWidth > 0f;
        boolean silhouetteEmitted = false;
        if (strokeSynthesis) {
            final GlyphMetrics sm = outline.strokedGlyph(codePoint, strokeWidth);
            if (sm != null) {
                silhouetteEmitted = true;
                final float su0 = sm.texX();
                final float sv0 = sm.texY();
                final float su1 = sm.texX() + sm.texWidth();
                final float sv1 = sm.texY() + sm.texHeight();
                final int silhouetteColor = strokeColor != null ? strokeColor : color;
                float sx = penX + scale * (sm.xOffset() - gm.xOffset());
                final float ssb = scale * sm.bearingY();
                final float ssh = scale * sm.height();
                final float ssw = scale * sm.width();
                float sxL = sx, sxR = sx + ssw, syT = -ssb, syB = -ssb - ssh;
                if (devGrid > 0f) {
                    sxL = snapToDeviceGrid(sxL, baseX, devGrid);
                    sxR = snapToDeviceGrid(sxR, baseX, devGrid);
                    syT = snapToDeviceGrid(syT, baseY, devGrid);
                    syB = snapToDeviceGrid(syB, baseY, devGrid);
                }
                (silhouetteSink != null ? silhouetteSink : quads).add(
                        quad(s, offX, offY, lineY, silhouetteColor, sm.textureId(),
                                sxL, syT, su0, sv1,
                                sxL, syB, su0, sv0,
                                sxR, syB, su1, sv0,
                                sxR, syT, su1, sv1));
            }
        }

        final float u0 = gm.texX();
        final float v0 = gm.texY();
        final float u1 = gm.texX() + gm.texWidth();
        final float v1 = gm.texY() + gm.texHeight();

        if (s.shadowCopies() > 0 && outline == null && borderPassEnabled) {
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
                quads.add(quad(s, offX, offY, lineY, color, gm.textureId(),
                        penX - ex, -sb - ey, u0, v1,
                        penX - ex, -sh - sb + ey * 2f, u0, v0,
                        penX + sw + ex * 2f, -sh - sb + ey * 2f, u1, v0,
                        penX + sw + ex * 2f, -sb - ey, u1, v1));
            }
        }

        float fxL = penX, fxR = penX + sw, fyT = -sb, fyB = -sh - sb;
        if (devGrid > 0f) {
            fxL = snapToDeviceGrid(fxL, baseX, devGrid);
            fxR = snapToDeviceGrid(fxR, baseX, devGrid);
            fyT = snapToDeviceGrid(fyT, baseY, devGrid);
            fyB = snapToDeviceGrid(fyB, baseY, devGrid);
        }
        // 填充只绘制一层，需按剪影颜色及是否实际发射补齐原版叠画层数。
        int fillColor = color;
        if (strokeSynthesis) {
            final int layers = strokeColor != null || !silhouetteEmitted
                    ? s.shadowCopies() + 1
                    : s.shadowCopies();
            fillColor = recombineSplitAlpha(color, layers);
        }
        quads.add(quad(s, offX, offY, lineY, fillColor, gm.textureId(),
                fxL, fyT, u0, v1,
                fxL, fyB, u0, v0,
                fxR, fyB, u1, v0,
                fxR, fyT, u1, v1));
    }

    private static float snapToDeviceGrid(final float local, final float base, final float grid) {
        return Math.round((local + base) * grid) / grid - base;
    }

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
        // 下划线的 y-2 属于局部坐标，必须参与剪切而不是叠加到行偏移。
        quads.add(quad(s, offX, offY, lineY, color, um.textureId(),
                penX, -2f - sb, u0, v1,
                penX, -2f - sh - sb, u0, v0,
                penX + sw, -2f - sh - sb, u1, v0,
                penX + sw, -2f - sb, u1, v1));
    }

    private static GlyphQuad quad(
            final TextRenderState s,
            final float offX,
            final float offY,
            final float lineY,
            final int color,
            final int textureId,
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
                color,
                textureId);
    }

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

    /** colorAlphas 缺失或越界时退回 highlightAlpha，避免原版异常调用序导致渲染崩溃。 */
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

    static int packColor(final int rgb, final float alpha) {
        final int a = ((byte) (255.0f * alpha)) & 0xFF;
        return (a << 24) | (rgb & 0xFFFFFF);
    }

    private static final class GlyphSourceCache implements OutlineGlyphProvider {
        private final GlyphProvider        glyphs;
        private final OutlineGlyphProvider outline;
        private final Map<Integer, GlyphMetrics> glyphCache   = new HashMap<>();
        private final Map<Long, GlyphMetrics>    strokedCache = new HashMap<>();

        GlyphSourceCache(final GlyphProvider glyphs, final OutlineGlyphProvider outline) {
            this.glyphs = glyphs;
            this.outline = outline;
        }

        @Override
        public GlyphMetrics glyph(final int codePoint) {
            if (glyphCache.containsKey(codePoint)) {
                return glyphCache.get(codePoint);
            }
            final GlyphMetrics gm = glyphs.glyph(codePoint);
            glyphCache.put(codePoint, gm);
            return gm;
        }

        @Override
        public GlyphMetrics strokedGlyph(final int codePoint, final float strokeWidthLogicalPx) {
            final long key = ((long) Float.floatToRawIntBits(strokeWidthLogicalPx) << 32) | codePoint;
            if (strokedCache.containsKey(key)) {
                return strokedCache.get(key);
            }
            final GlyphMetrics gm = outline.strokedGlyph(codePoint, strokeWidthLogicalPx);
            strokedCache.put(key, gm);
            return gm;
        }

        @Override
        public boolean synthesizesOutline() {
            return outline != null && outline.synthesizesOutline();
        }

        @Override
        public float currentBucketScale() {
            return outline.currentBucketScale();
        }

        @Override
        public Integer kerning(final int prevCodePoint, final int codePoint) {
            return glyphs.kerning(prevCodePoint, codePoint);
        }

        @Override
        public int nominalFontSize() {
            return glyphs.nominalFontSize();
        }

        @Override
        public int lineHeight() {
            return glyphs.lineHeight();
        }

        @Override
        public GlyphProvider forScale(final float scale) {
            return glyphs.forScale(scale);
        }

        @Override
        public void flushPendingUploads() {
            glyphs.flushPendingUploads();
        }

        @Override
        public boolean usesAtlasTexture() {
            return glyphs.usesAtlasTexture();
        }
    }
}
