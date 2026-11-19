package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.font.BitmapFont;
import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.font.AnimatedFontSize;
import github.kasuminova.ssoptimizer.common.font.FloatingTextFont;
import github.kasuminova.ssoptimizer.common.font.FontGlyphSources;
import github.kasuminova.ssoptimizer.common.font.TtfGlyphProvider;
import github.kasuminova.ssoptimizer.common.font.emit.TextStreamEmitter;
import github.kasuminova.ssoptimizer.common.font.emit.DirectTextEmitter;
import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import github.kasuminova.ssoptimizer.common.font.layout.GlyphProvider;
import github.kasuminova.ssoptimizer.common.font.layout.TextLayoutEngine;
import github.kasuminova.ssoptimizer.common.font.layout.TextPass;
import github.kasuminova.ssoptimizer.common.font.layout.TextRenderState;
import github.kasuminova.ssoptimizer.common.render.engine.TextLayoutDiagnostics;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.apache.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Color;
import java.nio.FloatBuffer;
import java.util.List;

@Mixin(targets = GameClassNames.BITMAP_FONT_RENDERER_DOTTED)
public abstract class BitmapFontRendererMixin implements FloatingTextFont {

    @Unique private boolean ssoptimizer$floatingGlyphs;
    @Unique private AnimatedFontSize ssoptimizer$scaleHistory;
    @Unique private TtfGlyphProvider ssoptimizer$observedFont;
    @Unique private float ssoptimizer$initialSize;

    @Override
    public void ssoptimizer$useFloatingGlyphs() {
        ssoptimizer$floatingGlyphs = true;
    }

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
        final boolean direct = !RenderThreadMode.isEnabled();
        if (direct && !ssoptimizer$needsDynamicGlyphs()) {
            return;
        }
        if (renderText != null && font == null) {
            ci.cancel();
            SSOPTIMIZER$LOGGER.info("trying to render non-null text with a null font");
            return;
        }
        GlyphProvider selected = FontGlyphSources.resolve(font, ssoptimizer$floatingGlyphs);
        if (!ssoptimizer$floatingGlyphs && !direct && selected instanceof TtfGlyphProvider ttf
                && GlDispatch.isMainRecordingThread() && ssoptimizer$isAnimated(ttf)) {
            selected = ttf.floatingTextGlyphs();
        }
        final GlyphProvider provider = selected;
        if (direct && !provider.usesAtlasTexture()) {
            return;
        }
        ci.cancel();
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
        final List<TextPass> passes;
        try {
            passes = TextLayoutEngine.layout(state, provider);
            provider.flushPendingUploads();
            if (direct) {
                DirectTextEmitter.emit(passes, blendSrcFactor, blendDstFactor);
            } else if (provider.usesAtlasTexture()) {
                TextStreamEmitter.emit(passes, blendSrcFactor, blendDstFactor);
            } else {
                TextStreamEmitter.emit(passes, font.getTexture(), blendSrcFactor, blendDstFactor);
            }
        } finally {
            TtfGlyphProvider.finishFloatingDraw(provider);
        }
        if (TextLayoutDiagnostics.isEnabled()) {
            int quadCount = 0;
            for (final TextPass pass : passes) {
                quadCount += pass.quads().size();
            }
            TextLayoutDiagnostics.recordV2Render(passes.size(), quadCount, requestedFontSize);
        }
    }

    @Unique
    private boolean ssoptimizer$isAnimated(final TtfGlyphProvider ttf) {
        if (ttf != ssoptimizer$observedFont) {
            ssoptimizer$observedFont = ttf;
            ssoptimizer$initialSize = requestedFontSize;
            ssoptimizer$scaleHistory = null;
            return false;
        }
        if (ssoptimizer$scaleHistory == null && ssoptimizer$initialSize == requestedFontSize) return false;
        final long now = System.nanoTime();
        final float inverse = 1f / ttf.nominalFontSize();
        if (ssoptimizer$scaleHistory == null) {
            ssoptimizer$scaleHistory = new AnimatedFontSize();
            ssoptimizer$scaleHistory.observe(ssoptimizer$initialSize * inverse, now);
        }
        return ssoptimizer$scaleHistory.observe(requestedFontSize * inverse, now);
    }

    private boolean ssoptimizer$needsDynamicGlyphs() {
        if (font == null || renderText == null) {
            return false;
        }
        for (int offset = 0; offset < renderText.length();) {
            final int codePoint = renderText.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (font.getGlyph(codePoint) == null && FontGlyphSources.missingGlyphMetrics(font, codePoint) != null) {
                return true;
            }
        }
        return false;
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
