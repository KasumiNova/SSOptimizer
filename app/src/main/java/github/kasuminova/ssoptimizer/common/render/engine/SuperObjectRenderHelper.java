package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.loading.LazyTextureManager;
import org.lwjgl.opengl.GL11;

/**
 * Emits one bitmap-font glyph quad (plus its shadow expansion passes) inside an
 * already-open {@code glBegin(GL_QUADS)} block.
 * <p>
 * The ASM rewrite extracts all glyph metrics and texture coordinates once, then
 * forwards them here so the hot path becomes a single helper call instead of
 * eight Java GL11 calls per quad.
 */
public final class SuperObjectRenderHelper {
    private SuperObjectRenderHelper() {
    }

    public static void renderGlyphQuad(
            float x, float y,
            int glyphWidth, int glyphHeight, int bearingY,
            float texX, float texY, float texWidth, float texHeight,
            float scale,
            int shadowCopies,
            float shadowScale) {
        final boolean managedFont = LazyTextureManager.isCurrentBoundManagedFontTexture();
        final boolean pixelSnappedVictorPath = shouldUsePixelSnappedVictorPath(
                LazyTextureManager.isCurrentBoundVictorPixelFontTexture(),
                scale
        );
        final boolean optimizedShadowPath = shouldUseOptimizedShadowPath(managedFont, shadowCopies);
        final boolean nativePath = SpriteRenderHelper.isNativeLoaded() && !pixelSnappedVictorPath && !optimizedShadowPath;
        TextRenderDiagnostics.recordGlyphQuad(
                glyphWidth, glyphHeight, bearingY,
                texWidth, texHeight,
                scale, shadowCopies,
                nativePath
        );
        if (nativePath) {
            nativeRenderGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY,
                    texX, texY, texWidth, texHeight, scale, shadowCopies, shadowScale);
        } else if (pixelSnappedVictorPath) {
            pixelSnappedRenderGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY,
                    texX, texY, texWidth, texHeight, scale, shadowCopies, shadowScale);
        } else if (optimizedShadowPath) {
            optimizedShadowRenderGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY,
                    texX, texY, texWidth, texHeight, scale, shadowCopies, shadowScale);
        } else {
            fallbackRenderGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY,
                    texX, texY, texWidth, texHeight, scale, shadowCopies, shadowScale);
        }
    }

    static boolean shouldUsePixelSnappedVictorPath(final boolean victorPixelFont,
                                                   final float scale) {
        return victorPixelFont && !isIntegralScale(scale);
    }

    static boolean shouldUseOptimizedShadowPath(final boolean managedFont,
                                                final int shadowCopies) {
        return managedFont && shadowCopies > 0;
    }

    static boolean isIntegralScale(final float scale) {
        return Float.isFinite(scale) && Math.abs(scale - Math.round(scale)) < 0.001f;
    }

    static SnappedGlyphQuad snappedGlyphQuad(final float x,
                                             final float y,
                                             final int glyphWidth,
                                             final int glyphHeight,
                                             final int bearingY,
                                             final float scale) {
        final float rawTop = y - scale * bearingY;
        final float left = snapToPixel(x);
        final float right = snapRangeEnd(x, x + scale * glyphWidth);
        final float top = snapToPixel(rawTop);
        final float bottom = snapRangeEnd(rawTop, y - scale * glyphHeight - scale * bearingY);
        return new SnappedGlyphQuad(left, top, right, bottom);
    }

    private static void pixelSnappedRenderGlyphQuad(
            float x, float y,
            int glyphWidth, int glyphHeight, int bearingY,
            float texX, float texY, float texWidth, float texHeight,
            float scale,
            int shadowCopies,
            float shadowScale) {
        for (int shadowIndex = 1; shadowIndex <= shadowCopies; shadowIndex++) {
            float shadowOffset = shadowIndex * shadowScale;
            final SnappedGlyphQuad shadowQuad = snappedQuadFromCorners(
                    x + shadowOffset,
                    y - scale * bearingY + shadowOffset,
                    x + scale * glyphWidth + shadowOffset,
                    y - scale * glyphHeight - scale * bearingY + shadowOffset
            );
            emitQuad(texX, texY, texWidth, texHeight, shadowQuad);
        }

        emitQuad(texX, texY, texWidth, texHeight,
                snappedGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY, scale));
    }

    private static void optimizedShadowRenderGlyphQuad(
            float x, float y,
            int glyphWidth, int glyphHeight, int bearingY,
            float texX, float texY, float texWidth, float texHeight,
            float scale,
            int shadowCopies,
            float shadowScale) {
        for (int shadowIndex = 1; shadowIndex <= shadowCopies; shadowIndex++) {
            final float shadowOffset = shadowIndex * shadowScale;
            emitQuad(texX, texY, texWidth, texHeight,
                    translatedGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY, scale, shadowOffset, shadowOffset));
        }

        emitQuad(texX, texY, texWidth, texHeight,
                translatedGlyphQuad(x, y, glyphWidth, glyphHeight, bearingY, scale, 0f, 0f));
    }

    private static void emitQuad(final float texX,
                                 final float texY,
                                 final float texWidth,
                                 final float texHeight,
                                 final SnappedGlyphQuad quad) {
        GL11.glTexCoord2f(texX, texY + texHeight);
        GL11.glVertex2f(quad.left(), quad.top());

        GL11.glTexCoord2f(texX, texY);
        GL11.glVertex2f(quad.left(), quad.bottom());

        GL11.glTexCoord2f(texX + texWidth, texY);
        GL11.glVertex2f(quad.right(), quad.bottom());

        GL11.glTexCoord2f(texX + texWidth, texY + texHeight);
        GL11.glVertex2f(quad.right(), quad.top());
    }

    private static SnappedGlyphQuad snappedQuadFromCorners(final float left,
                                                           final float top,
                                                           final float right,
                                                           final float bottom) {
        return new SnappedGlyphQuad(
                snapToPixel(left),
                snapToPixel(top),
                snapRangeEnd(left, right),
                snapRangeEnd(top, bottom)
        );
    }

    static SnappedGlyphQuad translatedGlyphQuad(final float x,
                                                final float y,
                                                final int glyphWidth,
                                                final int glyphHeight,
                                                final int bearingY,
                                                final float scale,
                                                final float dx,
                                                final float dy) {
        return new SnappedGlyphQuad(
                x + dx,
                y - scale * bearingY + dy,
                x + scale * glyphWidth + dx,
                y - scale * glyphHeight - scale * bearingY + dy
        );
    }

    private static float snapToPixel(final float value) {
        return Math.round(value);
    }

    private static float snapRangeEnd(final float start,
                                      final float end) {
        final float snappedStart = snapToPixel(start);
        float snappedEnd = snapToPixel(end);
        final float span = end - start;
        if (Math.abs(span) >= 0.001f && Math.abs(snappedEnd - snappedStart) < 0.001f) {
            final float minimumSpan = Math.max(1.0f, Math.round(Math.abs(span)));
            snappedEnd = snappedStart + Math.copySign(minimumSpan, span);
        }
        return snappedEnd;
    }

    record SnappedGlyphQuad(float left,
                            float top,
                            float right,
                            float bottom) {
    }

    private static void fallbackRenderGlyphQuad(
            float x, float y,
            int glyphWidth, int glyphHeight, int bearingY,
            float texX, float texY, float texWidth, float texHeight,
            float scale,
            int shadowCopies,
            float shadowScale) {
        float glyphWidthF = glyphWidth;
        float glyphHeightF = glyphHeight;
        float bearingYF = bearingY;

        for (int shadowIndex = 1; shadowIndex <= shadowCopies; shadowIndex++) {
            float shadowOffset = shadowIndex * shadowScale;
            float widthOffset = shadowOffset;
            float heightOffset = shadowOffset;

            if (glyphWidthF > glyphHeightF) {
                heightOffset *= glyphHeightF / glyphWidthF;
            }
            if (glyphHeightF > glyphWidthF) {
                widthOffset *= glyphWidthF / glyphHeightF;
            }

            GL11.glTexCoord2f(texX, texY + texHeight);
            GL11.glVertex2f(x - widthOffset, y - scale * bearingYF - heightOffset);

            GL11.glTexCoord2f(texX, texY);
            GL11.glVertex2f(
                    x - widthOffset,
                    y - scale * glyphHeightF - scale * bearingYF + heightOffset * 2.0f
            );

            GL11.glTexCoord2f(texX + texWidth, texY);
            GL11.glVertex2f(
                    x + scale * glyphWidthF + widthOffset * 2.0f,
                    y - scale * glyphHeightF - scale * bearingYF + heightOffset * 2.0f
            );

            GL11.glTexCoord2f(texX + texWidth, texY + texHeight);
            GL11.glVertex2f(
                    x + scale * glyphWidthF + widthOffset * 2.0f,
                    y - scale * bearingYF - heightOffset
            );
        }

        GL11.glTexCoord2f(texX, texY + texHeight);
        GL11.glVertex2f(x, y - scale * bearingYF);

        GL11.glTexCoord2f(texX, texY);
        GL11.glVertex2f(x, y - scale * glyphHeightF - scale * bearingYF);

        GL11.glTexCoord2f(texX + texWidth, texY);
        GL11.glVertex2f(x + scale * glyphWidthF, y - scale * glyphHeightF - scale * bearingYF);

        GL11.glTexCoord2f(texX + texWidth, texY + texHeight);
        GL11.glVertex2f(x + scale * glyphWidthF, y - scale * bearingYF);
    }

    static native void nativeRenderGlyphQuad(
            float x, float y,
            int glyphWidth, int glyphHeight, int bearingY,
            float texX, float texY, float texWidth, float texHeight,
            float scale,
            int shadowCopies,
            float shadowScale);
}