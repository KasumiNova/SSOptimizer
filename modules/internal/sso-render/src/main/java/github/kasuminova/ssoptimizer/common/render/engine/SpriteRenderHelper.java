package github.kasuminova.ssoptimizer.common.render.engine;

import github.kasuminova.ssoptimizer.bridge.opengl.GlDispatch;
import github.kasuminova.ssoptimizer.bridge.opengl.SpriteDraw;
import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import github.kasuminova.ssoptimizer.common.render.spritebatch.SpriteBatch;

public final class SpriteRenderHelper {
    static {
        NativeRuntime.ensureLoaded();
    }

    private SpriteRenderHelper() {
    }

    public static boolean isNativeLoaded() {
        return NativeRuntime.isGlReady();
    }

    public static void renderSprite(SpriteDraw draw) {
        if (SpriteBatch.getInstance().submitIfActive(draw)) {
            return;
        }
        if (GlDispatch.canSubmitOwnedDraw()) {
            draw.submitSingle();
        } else {
            draw.renderOutsideMainQueue();
        }
    }

    public static boolean renderNativeSpriteIfReady(
            float posX, float posY, float width, float height, float centerX, float centerY, float angle,
            byte red, byte green, byte blue, byte alpha, int blendSrc, int blendDest,
            float texX, float texY, float texWidth, float texHeight) {
        if (!NativeRuntime.isGlReady()) {
            return false;
        }
        nativeRenderSprite(posX, posY, width, height, centerX, centerY, angle,
                red & 0xff, green & 0xff, blue & 0xff, alpha & 0xff, blendSrc, blendDest,
                texX, texY, texWidth, texHeight);
        return true;
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
