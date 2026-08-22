package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFont;
import github.kasuminova.ssoptimizer.common.font.FontGlyphSources;
import github.kasuminova.ssoptimizer.common.font.emit.TextStreamEmitter;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;
import github.kasuminova.ssoptimizer.common.font.layout.TextLayoutEngine;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import github.kasuminova.ssoptimizer.common.font.layout.TextRenderState;
import github.kasuminova.ssoptimizer.common.render.engine.TextLayoutDiagnostics;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.List;

/**
 * BitmapFontRenderer 文本绘制路径的 Mixin 重写（v2 布局引擎管线）。
 * <p>
 * 注入目标：{@code com.fs.graphics.font.BitmapFontRenderer}<br>
 * 注入动机：原版逐字形渲染会产生大量 Java 侧 GL 调用，文本绘制路径在高 DPI 和大字号下开销明显。<br>
 * 注入效果：{@code render()} HEAD 无条件接管——状态快照 → TextLayoutEngine 布局 →
 * TextStreamEmitter 流式发射，原私有渲染链（含 display list）整体不再执行。
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
     * v2 渲染接管：render() 整体替换为「快照 → 字形源解析 → 布局引擎 → 流式发射」。
     * 字形源按字体身份分流：原版覆盖表命中且 native 栅格化可用 → TTF 动态图集
     * （quad 携带图集页 textureId，发射层分组换绑）；否则 → 位图直发（pass 级纹理）。
     *
     * @reason 新链路在保留 codepoint/颜色语义的层级重建文本绘制，替代原版私有渲染链。
     */
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$renderWithLayoutEngine(final CallbackInfo ci) {
        ci.cancel();
        // 与原版 render() 相同的空字体防护（原版 log4j info 后返回）
        if (renderText != null && font == null) {
            SSOPTIMIZER$LOGGER.info("trying to render non-null text with a null font");
            return;
        }
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
        // 字形源按字体身份解析——覆盖表命中且 native 可用走 TTF 动态图集，否则位图直发
        final GlyphProvider provider = FontGlyphSources.resolve(font);
        final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
        // 图集脏数据必须先于发射提交渲染线程（同帧命令顺序保证上传先于采样执行）
        provider.flushPendingUploads();
        if (provider.usesAtlasTexture()) {
            TextStreamEmitter.emit(passes, blendSrcFactor, blendDstFactor);
        } else {
            TextStreamEmitter.emit(passes, font.getTexture(), blendSrcFactor, blendDstFactor);
        }
        if (TextLayoutDiagnostics.isEnabled()) {
            int quadCount = 0;
            for (final TextPass pass : passes) {
                quadCount += pass.quads().size();
            }
            TextLayoutDiagnostics.recordV2Render(passes.size(), quadCount, requestedFontSize);
        }
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
}
