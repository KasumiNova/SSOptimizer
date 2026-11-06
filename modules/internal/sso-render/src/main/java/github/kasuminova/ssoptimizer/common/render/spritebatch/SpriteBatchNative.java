package github.kasuminova.ssoptimizer.common.render.spritebatch;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

public final class SpriteBatchNative {
    public static final int RESULT_GUARD_REJECTED    = -1;
    public static final int RESULT_EQUATION_MISMATCH = -2;
    public static final int RESULT_INVALID_BUFFER    = -3;
    public static final int RESULT_EXTENDED_STATE    = -4;
    public static final int RESULT_STATE_MISMATCH    = -5;

    static {
        NativeRuntime.ensureLoaded();
    }

    private SpriteBatchNative() {
    }

    static native int nativeSubmit(ByteBuffer verts, ByteBuffer indices, IntBuffer runTarget,
                                   int pendingQuads, int expectedBlendEquation, int requireExtendedState,
                                   float posX, float posY, float width, float height,
                                   float centerX, float centerY, float angle,
                                   int r, int g, int b, int a,
                                   float texX, float texY, float texWidth, float texHeight);

    // native 恢复 VBO 绑定不会更新 LWJGL2 StateTracker，调用方必须经 LWJGL 重绑。
    static native void nativeFlush(int vertexVboId, long vertexBase,
                                   int indexVboId, long indexBase,
                                   int quadCount,
                                   int textureId, int blendSrc, int blendDest, int blendEquation,
                                   int fbo, int viewportX, int viewportY, int viewportW, int viewportH,
                                   boolean alphaTestEnabled, int alphaFunc, float alphaRef,
                                   int r, int g, int b, int a,
                                   int prevMatrixMode);
}
