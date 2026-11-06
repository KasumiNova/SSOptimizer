package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.common.render.engine.ArcStripRenderHelper;
import github.kasuminova.ssoptimizer.common.render.engine.TexturedStripRenderHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.awt.Color;

@Mixin(targets = GameClassNames.TEXTURED_STRIP_RENDERER_DOTTED)
public abstract class TexturedStripRendererMixin {

    @Overwrite(remap = false)
    public static void renderTexturedStrip(
            TextureObject texture,
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            Color color,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale,
            boolean additive) {
        TexturedStripRenderHelper.renderTexturedStrip(
                texture, startX, startY, endX, endY, startWidth, endWidth,
                color, startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale, additive);
    }

    @Overwrite(remap = false)
    public static void renderArc(
            TextureObject texture,
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float width,
            Color color,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        ArcStripRenderHelper.renderArc(
                texture, x, y, startAngle, endAngle, segmentLength, radius, width,
                color, alphaStart, alphaEnd, alphaMult, additive);
    }

    @Overwrite(remap = false)
    public static void renderLineArc(
            float x, float y,
            float startAngle, float endAngle,
            float segmentLength,
            float radius, float lineWidth,
            Color color, Color dashColor,
            int dashSegments,
            int alphaStart, int alphaEnd,
            float alphaMult,
            boolean additive) {
        ArcStripRenderHelper.renderLineArc(
                x, y, startAngle, endAngle, segmentLength, radius, lineWidth,
                color, dashColor, dashSegments, alphaStart, alphaEnd, alphaMult, additive);
    }
}
