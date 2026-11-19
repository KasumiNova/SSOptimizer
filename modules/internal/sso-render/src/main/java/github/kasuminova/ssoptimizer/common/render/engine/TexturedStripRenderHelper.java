package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.common.render.EffectVertices;
import github.kasuminova.ssoptimizer.common.render.warroom.WarroomTaskLineBatch;
import org.lwjgl.opengl.GL11;

import java.awt.Color;

public final class TexturedStripRenderHelper {
    private TexturedStripRenderHelper() {
    }

    public static void renderTexturedStrip(
            com.fs.graphics.TextureObject texture,
            float startX, float startY,
            float endX, float endY,
            float startWidth, float endWidth,
            Color color,
            float startEdgeAlphaScale,
            float centerAlphaScale,
            float endEdgeAlphaScale,
            boolean additive) {
        if (WarroomTaskLineBatch.isCollecting()) {
            WarroomTaskLineBatch.addStrip(texture, startX, startY, endX, endY,
                    startWidth, endWidth, color,
                    startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale, additive,
                    TexturedStripRenderHelper::renderStripBatch);
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        texture.bind();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);

        int red = color.getRed();
        int green = color.getGreen();
        int blue = color.getBlue();
        int alpha = color.getAlpha();

        if (GlDispatch.canSubmitOwnedDraw()) {
            EffectVertices.submit(out -> emit(out, startX, startY, endX, endY, startWidth, endWidth,
                    red, green, blue, alpha, startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale));
        } else if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderTexturedStrip(startX, startY, endX, endY,
                    startWidth, endWidth,
                    red, green, blue, alpha,
                    startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale,
                    additive);
        } else {
            emit(EffectVertices.RECORDED, startX, startY, endX, endY,
                    startWidth, endWidth,
                    red, green, blue, alpha,
                    startEdgeAlphaScale, centerAlphaScale, endEdgeAlphaScale);
        }
    }

    static void emit(EffectVertices out,
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

        out.begin(GL11.GL_TRIANGLE_FAN);
        try {
            setColor(out, red, green, blue, scaleAlpha(alpha, centerAlphaScale));
            out.uv(0.5f, 0.5f);
            out.vertex(centerX, centerY);

            setColor(out, red, green, blue, scaleAlpha(alpha, startEdgeAlphaScale));
            out.uv(0.0f, 0.0f);
            out.vertex(startX - startOffsetX, startY - startOffsetY);
            out.uv(0.0f, 1.0f);
            out.vertex(startX + startOffsetX, startY + startOffsetY);

            setColor(out, red, green, blue, scaleAlpha(alpha, endEdgeAlphaScale));
            out.uv(1.0f, 1.0f);
            out.vertex(endX + endOffsetX, endY + endOffsetY);
            out.uv(1.0f, 0.0f);
            out.vertex(endX - endOffsetX, endY - endOffsetY);

            setColor(out, red, green, blue, scaleAlpha(alpha, startEdgeAlphaScale));
            out.uv(0.0f, 0.0f);
            out.vertex(startX - startOffsetX, startY - startOffsetY);
        } finally {
            out.end();
        }
    }

    private static void setColor(EffectVertices out, int red, int green, int blue, int alpha) {
        out.color((byte) red, (byte) green, (byte) blue, (byte) alpha);
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

    public static void beginStripBatch() {
        WarroomTaskLineBatch.beginCollect();
    }

    public static void endStripBatch() {
        WarroomTaskLineBatch.endCollect(TexturedStripRenderHelper::renderStripBatch);
    }

    public static void renderStripBatch(
            com.fs.graphics.TextureObject texture,
            boolean additive,
            float[] geometry,
            int[] colors,
            int stripCount) {
        if (stripCount <= 0) {
            return;
        }
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        texture.bind();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, additive ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA);

        if (GlDispatch.canSubmitOwnedDraw()) {
            float[] input = new float[Math.multiplyExact(stripCount, 9)];
            int[] rgba = new int[stripCount];
            System.arraycopy(geometry, 0, input, 0, input.length);
            System.arraycopy(colors, 0, rgba, 0, stripCount);
            EffectVertices.submit(out -> emitBatch(out, input, rgba, stripCount));
        } else if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderTexturedStripBatch(geometry, colors, stripCount);
        } else {
            emitBatch(EffectVertices.RECORDED, geometry, colors, stripCount);
        }
    }

    static void emitBatch(EffectVertices out, float[] geometry, int[] colors, int stripCount) {
        for (int strip = 0; strip < stripCount; strip++) {
            int base = strip * 9;
            int rgba = colors[strip];
            emit(out,
                    geometry[base], geometry[base + 1],
                    geometry[base + 2], geometry[base + 3],
                    geometry[base + 4], geometry[base + 5],
                    (rgba >>> 24) & 0xFF, (rgba >>> 16) & 0xFF, (rgba >>> 8) & 0xFF, rgba & 0xFF,
                    geometry[base + 6], geometry[base + 7], geometry[base + 8]);
        }
    }

    static native void nativeRenderTexturedStripBatch(
            float[] geometry,
            int[] colors,
            int stripCount);
}
