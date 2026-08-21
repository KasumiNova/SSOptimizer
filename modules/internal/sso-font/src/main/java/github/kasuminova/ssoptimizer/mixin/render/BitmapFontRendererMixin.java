package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFont;
import com.fs.graphics.font.BitmapGlyph;
import github.kasuminova.ssoptimizer.common.font.BitmapFontGlyphProvider;
import github.kasuminova.ssoptimizer.common.font.FontRenderEngine;
import github.kasuminova.ssoptimizer.common.font.RuntimeScaledFontCache;
import github.kasuminova.ssoptimizer.common.font.emit.TextStreamEmitter;
import github.kasuminova.ssoptimizer.common.font.layout.TextLayoutEngine;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import github.kasuminova.ssoptimizer.common.font.layout.TextRenderState;
import github.kasuminova.ssoptimizer.common.render.engine.BitmapFontRendererHelper;
import github.kasuminova.ssoptimizer.common.render.engine.TextLayoutDiagnostics;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * BitmapFontRenderer 文本绘制路径的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFontRenderer}<br>
 * 注入动机：原版逐字形渲染会产生大量 Java 侧 GL 调用，文本绘制路径在高 DPI 和大字号下开销明显。<br>
 * 注入效果（双引擎，{@link FontRenderEngine} 开关切换）：<br>
 * - v2（新布局引擎）：{@code render()} HEAD 接管——状态快照 → TextLayoutEngine 布局 →
 *   TextStreamEmitter 流式发射，原私有渲染链（含 display list）整体不再执行；<br>
 * - legacy（现行默认）：{@code drawGlyph} 替换为 helper/native 委托 + render HEAD 运行时缩放换字体，
 *   行为与重写前完全一致。P4 拆除 legacy 后本类只保留 v2 注入。
 */
@Mixin(targets = GameClassNames.BITMAP_FONT_RENDERER_DOTTED)
public abstract class BitmapFontRendererMixin {

    private static final Logger SSOPTIMIZER$LOGGER = Logger.getLogger(BitmapFontRendererMixin.class);

    @Shadow(remap = false) private BitmapFont font;
    @Shadow(remap = false) private float requestedFontSize;
    @Shadow(remap = false) private String renderText;
    @Shadow(remap = false) private float drawX;
    @Shadow(remap = false) private float drawY;
    @Shadow(remap = false) private Color textColor;
    @Shadow(remap = false) private Color outlineColor;
    @Shadow(remap = false) private Color highlightColor;
    @Shadow(remap = false) private boolean shadowEnabled;
    @Shadow(remap = false) private boolean borderEnabled;
    @Shadow(remap = false) private boolean underlineEnabled;
    @Shadow(remap = false) private boolean visible;
    @Shadow(remap = false) private float borderAlpha;
    @Shadow(remap = false) private float shadowAlpha;
    @Shadow(remap = false) private float textAlpha;
    @Shadow(remap = false) private float highlightAlpha;
    @Shadow(remap = false) private float[] colorAlphas;
    @Shadow(remap = false) private int blendSrcFactor;
    @Shadow(remap = false) private int blendDstFactor;
    @Shadow(remap = false) private int selectionStart;
    @Shadow(remap = false) private int selectionEnd;
    @Shadow(remap = false) private boolean[] charSelectionFlags;
    @Shadow(remap = false) private int[] charWordIndexes;
    @Shadow(remap = false) private Color[] wordColors;
    @Shadow(remap = false) private float shadowOffsetX;
    @Shadow(remap = false) private float shadowOffsetY;
    @Shadow(remap = false) private int shadowCopies;
    @Shadow(remap = false) private float shadowScale;
    @Shadow(remap = false) private FloatBuffer shearMatrix;
    @Shadow(remap = false) protected boolean isCompactFont;

    @Shadow(remap = false)
    protected abstract void updateCompactFontFlag();

    /**
     * v2 渲染接管：render() 整体替换为「快照 → 布局引擎 → 流式发射」。
     *
     * @reason 新链路在保留 codepoint/颜色语义的层级重建文本绘制，替代原版私有渲染链；
     *         legacy 模式下不接管（返回后走原版 render）。
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$renderWithLayoutEngine(final CallbackInfo ci) {
        if (!FontRenderEngine.isV2()) {
            return;
        }
        ci.cancel();
        // 与原版 render() 相同的空字体防护（原版 log4j info 后返回）
        if (renderText != null && font == null) {
            SSOPTIMIZER$LOGGER.info("trying to render non-null text with a null font");
            return;
        }
        // P2 过渡期：位图字形源下沿用运行时缩放换字体保持高缩放档位清晰
        // （P3 动态图集按有效像素尺寸精确栅格化后移除本调用，连同 RuntimeScaledFontCache 一并拆除）；
        // 显式在此调用而非依赖注入顺序，保证快照一定取到换装后的字体
        font = (BitmapFont) RuntimeScaledFontCache.resolveScaledFont(font, requestedFontSize);
        requestedFontSize = RuntimeScaledFontCache.adjustRequestedFontSize(font, requestedFontSize);
        // compact 标记的惰性刷新与原版 renderText 内的 updateCompactFontFlag 对齐
        updateCompactFontFlag();

        final TextRenderState state = TextRenderState.builder(renderText)
                .draw(drawX, drawY)
                .fontSize(requestedFontSize)
                .textColor(textColor.getRGB() & 0xFFFFFF, textAlpha)
                .outlineColor(outlineColor.getRGB() & 0xFFFFFF)
                .borderAlpha(borderAlpha)
                .shadowAlpha(shadowAlpha)
                .highlightColor(highlightColor.getRGB() & 0xFFFFFF, highlightAlpha)
                .wordColors(ssoptimizer$wordColorRgbs(), colorAlphas)
                .selection(selectionStart, selectionEnd)
                .charSelection(charSelectionFlags, charWordIndexes)
                .underlineEnabled(underlineEnabled)
                .shadowEnabled(shadowEnabled)
                .borderEnabled(borderEnabled)
                .shadowOffset(shadowOffsetX, shadowOffsetY)
                .outline(shadowCopies, shadowScale)
                .compactFont(isCompactFont)
                .shear(shearMatrix != null ? shearMatrix.get(4) : 0f)
                .visible(visible)
                .build();
        final List<TextPass> passes = TextLayoutEngine.layout(state, new BitmapFontGlyphProvider(font));
        TextStreamEmitter.emit(passes, font.getTexture(), blendSrcFactor, blendDstFactor);
    }

    /** wordColors（Color[]）转 RGB int[]；null 项保持 null 语义由引擎侧守卫处理。 */
    private int[] ssoptimizer$wordColorRgbs() {
        if (wordColors == null) {
            return null;
        }
        final int[] rgbs = new int[wordColors.length];
        for (int i = 0; i < wordColors.length; i++) {
            final Color color = wordColors[i];
            rgbs[i] = color != null ? color.getRGB() & 0xFFFFFF : 0;
        }
        return rgbs;
    }

    /**
     * 渲染入口：按屏幕缩放解析换用缩放字体，并回写调整后的请求字号（legacy 路径）。
     *
     * @reason 原 ASM 处理器在 render()V 入口插入字体缩放换字体逻辑，迁移为等价的 @Inject(HEAD)；
     *         v2 引擎接管时缩放由新链路负责（P3 动态图集），本注入跳过。
     */
    @Inject(method = "render", at = @At("HEAD"), remap = false)
    private void ssoptimizer$resolveScaledFontAtRenderHead(final CallbackInfo ci) {
        if (FontRenderEngine.isV2()) {
            return;
        }
        font = (BitmapFont) RuntimeScaledFontCache.resolveScaledFont(font, requestedFontSize);
        requestedFontSize = RuntimeScaledFontCache.adjustRequestedFontSize(font, requestedFontSize);
    }

    /**
     * 单个字形输出整体替换为 helper/native 委托（legacy 路径；v2 下原私有链不执行，本方法不会被调用）。
     *
     * @param x     字形基准 X
     * @param y     字形基准 Y
     * @param glyph 字形度量
     * @param scale 当前字号缩放
     * @param blend 混合标记（替换体不区分，保持签名一致）
     * @reason 原 ASM 处理器整体替换 drawGlyph 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    private void drawGlyph(final float x, final float y, final BitmapGlyph glyph, final float scale, final boolean blend) {
        TextLayoutDiagnostics.recordGlyphLayout(
                glyph.getGlyphId(), glyph.getXOffset(), glyph.getXAdvance(),
                System.identityHashCode(font), scale, requestedFontSize,
                font.getNominalFontSize(), font.getLineHeight());
        BitmapFontRendererHelper.renderGlyphQuad(
                x, y,
                glyph.getWidth(), glyph.getHeight(), glyph.getBearingY(),
                glyph.getTexX(), glyph.getTexY(), glyph.getTexWidth(), glyph.getTexHeight(),
                scale, shadowCopies, shadowScale);
    }
}
