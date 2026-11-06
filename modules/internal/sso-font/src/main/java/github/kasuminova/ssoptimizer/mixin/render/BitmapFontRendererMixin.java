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

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void ssoptimizer$renderWithLayoutEngine(final CallbackInfo ci) {
        ci.cancel();
        if (renderText != null && font == null) {
            SSOPTIMIZER$LOGGER.info("trying to render non-null text with a null font");
            return;
        }
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
        final GlyphProvider provider = FontGlyphSources.resolve(font);
        final List<TextPass> passes = TextLayoutEngine.layout(state, provider);
        // 图集上传必须先于文本发射提交，保证同帧采样前完成纹理更新。
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
