package github.kasuminova.ssoptimizer.common.font.layout;

import github.kasuminova.ssoptimizer.common.render.engine.TextScaleBuckets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 *   <li>display list 缓存不复制——新链路逐帧布局，无列表语义；</li>
 *   <li>P3 起：TTF 描边合成源（{@link OutlineGlyphProvider}）下边框/outline 改为
 *       主 pass 内剪影 quad 垫底单 pass（§4.5），阴影偏移在屏幕像素空间取整；
 *       零尺寸字形（'{'/'}' 空格化等占位符）不产 quad，只推进 penX。</li>
 * </ul>
 * 坐标系：输出顶点为绝对坐标（drawX/drawY 与 pass 偏移已烘焙），
 * y 向下为正（与原版顶点值一致，外部模型矩阵由调用方维持）。
 */
public final class TextLayoutEngine {

    /** 缺失字形回退码点（'?'），原版硬编码 glyphs[63]。 */
    private static final int FALLBACK_GLYPH = 63;
    /** 下划线字形码点（'_'），原版硬编码 glyphs[95]。 */
    private static final int UNDERLINE_GLYPH = 95;
    /** 边框模式的剪影宽度（逻辑像素），对应原版 4 向 ±1px 偏移的覆盖半径。 */
    private static final float BORDER_STROKE_WIDTH = 1f;

    /**
     * 描边栅格化合成开关：{@code -Dssoptimizer.font.stroke.synthesize=false} 关闭，
     * 默认开启（TTF 源下边框/outline 单 pass 剪影合成，见设计文档 §4.5）。
     * 静态可变字段供测试切换；位图路径不受本开关影响（源无法重栅格化，恒多 pass）。
     */
    static volatile boolean strokeSynthesizeEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.stroke.synthesize", "true"));

    /**
     * 阴影 pass 总开关：{@code -Dssoptimizer.font.shadow=false} 关闭所有字体的
     * 阴影偏移副本（边框/描边剪影不受影响），默认开启。临时诊断用途。
     */
    static volatile boolean shadowPassEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.shadow", "true"));

    /**
     * 边框/描边总开关：{@code -Dssoptimizer.font.border=false} 关闭所有字体的
     * 边框 4 向 pass 与描边剪影 quad（含 outline 放大副本合成），默认开启。
     * 临时诊断用途；关闭后等同于渲染状态 borderEnabled=false、shadowCopies=0。
     */
    static volatile boolean borderPassEnabled =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.font.border", "true"));

    private TextLayoutEngine() {
    }

    /**
     * 对一次 render() 调用做完整布局。
     *
     * @param s       渲染状态快照
     * @param glyphs  字形度量来源
     * @return 有序 pass 序列（边框/阴影在前，主 pass 在最后；compact 字体每逻辑 pass 展开为 3 个；
     *         TTF 描边合成路径下边框/outline 不产独立 pass，剪影 quad 内联在主 pass 各字形之前）
     */
    public static List<TextPass> layout(final TextRenderState s, final GlyphProvider glyphs) {
        // 尺寸档位视图：TTF 源据此选定 size bucket（含屏幕缩放量化），位图源返回 this
        final GlyphProvider sized = glyphs.forScale(s.fontSize() / glyphs.nominalFontSize());
        final float scale = s.fontSize() / sized.nominalFontSize();
        final int iterations = s.compactFont() ? 3 : 1;
        final OutlineGlyphProvider outline = strokeSynthesizeEnabled
                && sized instanceof OutlineGlyphProvider candidate
                && candidate.synthesizesOutline()
                ? candidate : null;
        // 单次 render 的逐码点缓存：shadow/边框/主各 pass（及 compact 迭代）会对同一
        // 码点重复查询，TTF 路径每次查询都进全图集锁 + 双 Map 查找——缓存后每码点
        // 每 render 只进一次。缓存对象不跨 render 存活，无淘汰/上下文一致性问题。
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

    /**
     * TTF 描边合成路径（§4.5）：边框/outline 不再多 pass 叠位图，主 pass 内每字形
     * 先发描边剪影 quad（描边色垫底）再发填充 quad；阴影偏移黑副本维持独立 pass，
     * 但偏移量在屏幕像素空间取整（乘以 bucketScale 取整再除回），消除位图空间
     * 偏移经非整数缩放映射后的错位采样。
     * <p>
     * alpha 重聚合：原版 setAlpha 按「shadowCopies+1 层放大副本 + 主字形叠画」把目标
     * alpha 预分解进 textAlpha/borderAlpha（叠画后累计回目标值）；本路径剪影/填充
     * 各只画一层，直接使用预分解值会比原版透明得多（实机症状：战斗浮字半透明、
     * 面板标签「发黑」只剩描边）。因此边框剪影 alpha 按原版 4 向边框 pass ×
     * (copies+1) 层的累计量重聚合，填充层在 emitGlyph 内按剩余层数重聚合
     * （见 emitGlyph 注释）。
     */
    private static void layoutWithStrokeSynthesis(
            final List<TextPass> passes,
            final TextRenderState s,
            final GlyphProvider sized,
            final OutlineGlyphProvider outline,
            final float scale,
            final int iterations) {
        // 阴影 pass：原版 border 与 shadow 互斥（else-if），剪影合成路径保持该语义
        final boolean border = s.borderEnabled() && borderPassEnabled;
        if (!border && s.shadowEnabled() && shadowPassEnabled) {
            final float bucketScale = outline.currentBucketScale();
            final float snapX = Math.round(s.shadowOffsetX() * bucketScale) / bucketScale;
            final float snapY = Math.round(s.shadowOffsetY() * bucketScale) / bucketScale;
            buildPass(passes, s, sized, scale, snapX, snapY,
                    packColor(s.outlineColorRgb(), s.shadowAlpha()), false, iterations, null, 0f, null);
        }

        // 剪影宽度与颜色：边框与 outline 同时存在时合并为一个剪影（宽度取大者，描边色）；
        // 仅 outline 时剪影跟随各字形当前色（原版放大副本用字形色），仅边框时用描边色。
        // 描边色剪影的 alpha 重聚合：原版边框 = 4 向 pass × (copies+1) 层叠画，
        // 单剪影需按 4*(copies+1) 层累计量补齐，否则边框远薄于原版
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

    /** 原版 alpha 预分解的逆运算：perLayer 经 layers 层叠画后的累计 alpha。 */
    private static float recombineLayers(final float perLayerAlpha, final int layers) {
        if (layers <= 1) {
            return perLayerAlpha;
        }
        return 1f - (float) Math.pow(1f - perLayerAlpha, layers);
    }

    /** {@link #recombineLayers} 的 ARGB 字节级形态（填充色在 emitGlyph 内动态切换，无法提前按 float 聚合）。 */
    private static int recombineSplitAlpha(final int argb, final int layers) {
        if (layers <= 1) {
            return argb;
        }
        final float perLayer = ((argb >>> 24) & 0xFF) / 255f;
        final int combined = Math.min(255, Math.round((1f - (float) Math.pow(1f - perLayer, layers)) * 255f));
        return (combined << 24) | (argb & 0xFFFFFF);
    }

    /**
     * 生成一个逻辑 pass（compact 字体展开为 iterations 个 TextPass）。
     *
     * @param offX/offY          pass 平移（边框 ±1px / 阴影偏移 / 主 pass 0）
     * @param baseColor          pass 起始颜色（ARGB）
     * @param selectionColoring  是否启用选区/高亮着色（仅主 pass，且 visible 时强制关闭）
     * @param outline            描边合成来源（非 null 时主 pass 每字形先发剪影 quad）
     * @param strokeWidth        剪影宽度（逻辑像素，0 = 不合成）
     * @param strokeColor        剪影固定颜色（ARGB）；null = 跟随各字形当前色
     *                           （原版 outline 放大副本用字形色）
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
            final int iterations,
            final OutlineGlyphProvider outline,
            final float strokeWidth,
            final Integer strokeColor) {
        final boolean coloring = selectionColoring && !s.visible();
        final int textColor = packColor(s.textColorRgb(), s.textAlpha());
        final String text = s.text();

        for (int iter = 0; iter < iterations; iter++) {
            final List<GlyphQuad> fills = new ArrayList<>();
            // 描边合成主 pass：剪影 quad 集中到 pass 头部（同纹理连续），填充随后——
            // 逐字形交错（S,F,S,F…）会让发射层每字形切 2 个纹理段（2N 条帧命令 /
            // 2N 次 4 顶点 draw call）；分组后每 pass 段数收敛到剪影组 + 填充组。
            // 绘制语义不变：剪影全局垫底于填充，与原版 shadowCopies「先副本后主字形」同构。
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

    /**
     * 单字形 quad：描边合成路径（outline != null 且 strokeWidth &gt; 0）产描边剪影 quad
     * （填充几何按描边宽度四向外扩，采样剪影槽位 UV）与填充 quad——剪影写入
     * {@code silhouetteSink}（非 null 时由调用方集中到 pass 头部，见 buildPass），
     * 填充写入 {@code quads}；位图路径维持原版
     * 的 shadowCopies 放大副本循环（先于主 quad 发射，横纵扩张按宽高比不对称缩放）。
     * 零尺寸字形（'{'/'}' 空格化等占位符）不产任何 quad——只推进 penX，
     * 与原版 bake 后不产可见像素的语义对齐（同时修正位图路径的退化 quad）。
     */
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

        // TTF 图集路径：quad 边吸附到设备像素网格（bucket 网格），消除逐字亚像素
        // 采样相位差（观感：字形边缘粗细/阴影逐字不一致、毛边）。槽位尺寸在 bucket
        // 网格上是整数，scale==1（fontSize==nominal）时吸附后得 1:1 texel 映射。
        // 仅无剪切（shear==0）时吸附——shear 下 x 依赖 y，网格语义不成立；
        // 位图路径（outline==null）维持原版逐字浮点坐标语义。
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
                // 剪影盒 = 描边槽位的并集画布盒（provider 侧已含描边外扩与墨迹溢出，
                // 度量为设备像素 ÷ bucketScale 的亚像素逻辑值），锚定同一落笔原点：
                // penX 已含填充 xOffset，此处换算相对偏移。引擎不再手工外扩几何
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
        // 填充 alpha 重聚合（描边合成路径专属）：state 携带的 alpha 是原版按
        // 「copies+1 层叠画」预分解的单层值，本路径填充只画一层必须补回累计量——
        // 描边色剪影（黑，异色）：填充需补齐全部 copies+1 层；
        // 随字色剪影（同色）：剪影本身充当一层，填充补 copies 层；
        // 剪影栅格化缺失时填充独自承担 copies+1 层。
        // 位图路径（outline==null）维持原版逐层叠画语义，不重聚合。
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

    /** 局部坐标吸附到设备像素网格：换算含 base 的绝对坐标取整后折回局部。 */
    private static float snapToDeviceGrid(final float local, final float base, final float grid) {
        return Math.round((local + base) * grid) / grid - base;
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
        quads.add(quad(s, offX, offY, lineY, color, um.textureId(),
                penX, -2f - sb, u0, v1,
                penX, -2f - sh - sb, u0, v0,
                penX + sw, -2f - sh - sb, u1, v0,
                penX + sw, -2f - sb, u1, v1));
    }

    /**
     * 组装绝对坐标 quad：局部 y（vyLocal，相对行基线）经 shear 折算到 x，
     * 行偏移 lineY 在 shear 之后叠加（等价原版 drawGlyphs 的 translate(0,lineY)+multMatrix 顺序）。
     * textureId 从 GlyphMetrics 透传（位图路径恒 0，发射层忽略）。
     */
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

    /**
     * 单次 render 的逐码点查询缓存（{@link #layout} 内创建、随调用结束丢弃）：
     * 各 pass 与 compact 迭代对同一码点的重复 glyph()/strokedGlyph() 查询只穿透
     * 一次到下游来源。kerning 不进缓存（位图实现是纯 fnt 数组读，无穿透开销）。
     * 不跨 render 存活，因此无需处理图集淘汰/上下文重建的失效问题。
     */
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
