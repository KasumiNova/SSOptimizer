package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.warroom.StripBatchRenderer;
import github.kasuminova.ssoptimizer.common.render.warroom.WarroomTaskLineBatch;
import org.lwjgl.opengl.GL11;

import java.awt.*;

/**
 * 引擎图条渲染工具。
 * <p>
 * 将原版引擎逐元素的 immediate-mode GL 调用替换为批量 VertexArray，
 * 显著减少 draw call 数量，提升战斗场景的渲染性能。
 */
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
            // 帧内批量收集激活（指挥界面任务连线渲染区间）：仅入队参数，flush 时统一提交。
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

    /**
     * 开启帧内条带批量收集（指挥界面任务连线渲染区间入口调用）。
     * <p>
     * 开关关闭时本方法无操作，条带渲染保持逐条路径。
     */
    public static void beginStripBatch() {
        WarroomTaskLineBatch.beginCollect();
    }

    /**
     * 结束帧内条带批量收集并一次性提交（渲染区间出口调用）。
     */
    public static void endStripBatch() {
        WarroomTaskLineBatch.endCollect(TexturedStripRenderHelper::renderStripBatch);
    }

    /**
     * 批量渲染一帧内收集的同纹理、同混合模式条带（{@link StripBatchRenderer} 实现）。
     * <p>
     * 只做一次纹理绑定与混合状态设置，随后一次性提交全部条带：
     * native 可用时将所有条带的三角扇展开为三角形列表后单次 draw call；
     * 否则复用单条回退路径逐条绘制（仍共享一次状态设置）。
     *
     * @param texture    本批共用纹理
     * @param additive   本批共用混合模式
     * @param geometry   几何数组，每条带 9 个 float（布局见 {@link StripBatchRenderer}）
     * @param colors     颜色数组，每条带 1 个 0xRRGGBBAA 打包 int
     * @param stripCount 实际条带数量
     */
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

        if (SpriteRenderHelper.isNativeLoaded()) {
            nativeRenderTexturedStripBatch(geometry, colors, stripCount);
            return;
        }
        for (int strip = 0; strip < stripCount; strip++) {
            int base = strip * 9;
            int rgba = colors[strip];
            fallbackRenderTexturedStrip(
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
