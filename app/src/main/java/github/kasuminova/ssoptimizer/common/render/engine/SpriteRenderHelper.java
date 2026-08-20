package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.lwjgl.opengl.GL11;

/**
 * Targeted single-sprite renderer that replaces the original Sprite.render/renderNoBind
 * GL call cascade (17–19 LWJGL JNI calls) with a single native JNI call.
 * Falls back to direct GL11 calls when the native library is not loaded.
 */
public final class SpriteRenderHelper {
    private static final float DEG_TO_RAD = 0.017453292519943295769f;

    static {
        NativeRuntime.ensureLoaded();
    }

    private SpriteRenderHelper() {
    }

    public static boolean isNativeLoaded() {
        return NativeRuntime.isGlReady();
    }

    /**
     * Render a textured quad with full matrix/state setup.
     * Called from Mixin-rewritten {@code Sprite.render} / {@code Sprite.renderNoBind}.
     */
    public static void renderSprite(
            float posX, float posY,
            float width, float height,
            float centerX, float centerY,
            float angle,
            int colorR, int colorG, int colorB, int colorA,
            int blendSrc, int blendDest,
            float texX, float texY, float texWidth, float texHeight) {
        if (NativeRuntime.isGlReady()) {
            nativeRenderSprite(posX, posY, width, height, centerX, centerY, angle,
                    colorR, colorG, colorB, colorA, blendSrc, blendDest,
                    texX, texY, texWidth, texHeight);
        } else {
            fallbackRenderSprite(posX, posY, width, height, centerX, centerY, angle,
                    colorR, colorG, colorB, colorA, blendSrc, blendDest,
                    texX, texY, texWidth, texHeight);
        }
    }

    private static void fallbackRenderSprite(
            float posX, float posY,
            float width, float height,
            float centerX, float centerY,
            float angle,
            int colorR, int colorG, int colorB, int colorA,
            int blendSrc, int blendDest,
            float texX, float texY, float texWidth, float texHeight) {
        float cx = (centerX != -1.0f && centerY != -1.0f) ? centerX : width * 0.5f;
        float cy = (centerX != -1.0f && centerY != -1.0f) ? centerY : height * 0.5f;
        float originX = posX + width * 0.5f;
        float originY = posY + height * 0.5f;

        float x0;
        float y0;
        float x1;
        float y1;
        float x2;
        float y2;
        float x3;
        float y3;

        if (angle == 0.0f) {
            x0 = originX - cx;
            y0 = originY - cy;
            x1 = x0;
            y1 = y0 + height;
            x2 = x0 + width;
            y2 = y1;
            x3 = x2;
            y3 = y0;
        } else {
            float radians = angle * DEG_TO_RAD;
            float sinA = (float) Math.sin(radians);
            float cosA = (float) Math.cos(radians);

            float localX0 = -cx;
            float localY0 = -cy;
            float localX1 = -cx;
            float localY1 = height - cy;
            float localX2 = width - cx;
            float localY2 = height - cy;
            float localX3 = width - cx;
            float localY3 = -cy;

            x0 = originX + localX0 * cosA - localY0 * sinA;
            y0 = originY + localX0 * sinA + localY0 * cosA;
            x1 = originX + localX1 * cosA - localY1 * sinA;
            y1 = originY + localX1 * sinA + localY1 * cosA;
            x2 = originX + localX2 * cosA - localY2 * sinA;
            y2 = originY + localX2 * sinA + localY2 * cosA;
            x3 = originX + localX3 * cosA - localY3 * sinA;
            y3 = originY + localX3 * sinA + localY3 * cosA;
        }

        GL11.glColor4ub((byte) colorR, (byte) colorG, (byte) colorB, (byte) colorA);

        /* ── GL state + textured quad（状态命令编码进顶点流，段外执行）─── */
        // bridge 流内入口用全限定名：本类编译期引用 org.lwjgl.opengl.GL11（常量
        // 内联 + ASM 重定向把方法调用改写为 bridge），流内状态指令是 bridge 专有
        github.kasuminova.ssoptimizer.bridge.opengl.GL11.streamEnable(GL11.GL_TEXTURE_2D);
        github.kasuminova.ssoptimizer.bridge.opengl.GL11.streamEnable(GL11.GL_BLEND);
        github.kasuminova.ssoptimizer.bridge.opengl.GL11.streamBlendFunc(blendSrc, blendDest);

        // 整组 begin..end 压成一条流内融合指令（编码 13 次流调用 → 1 次；
        // tex 角点顺序与原逐顶点序列一致）
        github.kasuminova.ssoptimizer.bridge.opengl.GL11.streamSpriteQuad(
                x0, y0, x1, y1, x2, y2, x3, y3, texX, texY, texWidth, texHeight);

        /* ── Cleanup（流内 disable，段外执行，保持与原版一致的 blend 关闭）── */
        github.kasuminova.ssoptimizer.bridge.opengl.GL11.streamDisable(GL11.GL_BLEND);
    }

    static native void nativeRenderSprite(
            float posX, float posY,
            float width, float height,
            float centerX, float centerY,
            float angle,
            int colorR, int colorG, int colorB, int colorA,
            int blendSrc, int blendDest,
            float texX, float texY, float texWidth, float texHeight);
}
