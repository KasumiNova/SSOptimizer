package github.kasuminova.ssoptimizer.render.engine;

import org.lwjgl.opengl.GL11;

import java.awt.*;

public final class TexturedStripRenderHelper {
    private TexturedStripRenderHelper() {
    }

    public static void renderTexturedStrip(
            com.fs.graphics.Object texture,
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            Color color,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale,
            boolean additive) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        texture.Ø00000();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);

        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int alpha = color.getAlpha();

        if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderTexturedStrip(startX, startY, endX, endY,
                    startWidth, endWidth,
                    red, green, blue, alpha,
                    startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale,
                    additive);
        } else {
            fallbackRenderTexturedStrip(startX, startY, endX, endY,
                    startWidth, endWidth,
                    red, green, blue, alpha,
                    startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale);
        }
    }

    private static void fallbackRenderTexturedStrip(
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            int red, int green, int blue, int alpha,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale) {
        float dx = endX - startX;
        float dy = endY - startY;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float invLength = length == 0.0f ? 0.0f : 1.0f / length;
        float normalX = dy * invLength;
        float normalY = -dx * invLength;

        float startOffsetX = normalX * startWidth * 0.5f;
        float startOffsetY = normalY * startWidth * 0.5f;
        float endOffsetX = normalX * endWidth * 0.5f;
        float endOffsetY = normalY * endWidth * 0.5f;

        float centerX = (startX + endX) * 0.5f;
        float centerY = (startY + endY) * 0.5f;

        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        setColor(red, green, blue, scaleAlpha(alpha, centerAlphaScale));
        GL11.glTexCoord2f(0.5f, 0.5f);
        GL11.glVertex2f(centerX, centerY);

        setColor(red, green, blue, scaleAlpha(alpha, startEdgeAlphaScale));
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2f(startX - startOffsetX, startY - startOffsetY);
        GL11.glTexCoord2f(0.0f, 1.0f);
        GL11.glVertex2f(startX + startOffsetX, startY + startOffsetY);

        setColor(red, green, blue, scaleAlpha(alpha, endEdgeAlphaScale));
        GL11.glTexCoord2f(1.0f, 1.0f);
        GL11.glVertex2f(endX + endOffsetX, endY + endOffsetY);
        GL11.glTexCoord2f(1.0f, 0.0f);
        GL11.glVertex2f(endX - endOffsetX, endY - endOffsetY);

        setColor(red, green, blue, scaleAlpha(alpha, startEdgeAlphaScale));
        GL11.glTexCoord2f(0.0f, 0.0f);
        GL11.glVertex2f(startX - startOffsetX, startY - startOffsetY);
        GL11.glEnd();
    }

    private static void setColor(int red, int green, int blue, int alpha) {
        GL11.glColor4ub((byte) red, (byte) green, (byte) blue, (byte) alpha);
    }

    private static int scaleAlpha(int baseAlpha, float scale) {
        return clampColorComponent((int) (baseAlpha * scale));
    }

    private static int clampColorComponent(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    static native void nativeRenderTexturedStrip(
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            int red, int green, int blue, int alpha,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale,
            boolean additive);
}