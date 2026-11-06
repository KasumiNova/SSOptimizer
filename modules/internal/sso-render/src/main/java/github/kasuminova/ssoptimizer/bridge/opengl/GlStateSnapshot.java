package github.kasuminova.ssoptimizer.bridge.opengl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** 仅在渲染线程采集状态；实例作为一次性传输载体使用。 */
final class GlStateSnapshot {
    int currentProgram;
    int activeTexture;
    int drawBuffer;
    final int[] viewport = new int[4];
    int matrixMode;
    final int[] texture2dBinding = new int[SimulatedGlState.MAX_TEXTURE_UNITS];
    final float[] modelviewTop = new float[16];
    final float[] projectionTop = new float[16];
    final float[] textureMatrixTop = new float[16];
    final boolean[] capEnabled = new boolean[SimulatedGlState.TRACKED_CAPS.length];
    int blendEquation;
    int alphaFunc;
    float alphaRef;
    int arrayBufferBinding;
    int elementArrayBufferBinding;
    int framebufferBinding;

    static GlStateSnapshot capture() {
        final GlStateSnapshot s = new GlStateSnapshot();
        s.currentProgram = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL20.GL_CURRENT_PROGRAM);
        s.activeTexture = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        s.drawBuffer = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_DRAW_BUFFER);
        // LWJGL2 要求该缓冲至少剩余 16 个元素，视口实际只写入 4 个。
        final IntBuffer intBuf = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asIntBuffer();
        org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_VIEWPORT, intBuf);
        intBuf.get(s.viewport);
        s.matrixMode = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_MATRIX_MODE);
        for (int unit = 0; unit < SimulatedGlState.MAX_TEXTURE_UNITS; unit++) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
            s.texture2dBinding[unit] =
                    org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
        }
        org.lwjgl.opengl.GL13.glActiveTexture(s.activeTexture);
        // GL 无法查询矩阵栈深，采入侧将深度视为 1。
        readMatrixTop(org.lwjgl.opengl.GL11.GL_MODELVIEW, org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX,
                s.modelviewTop);
        readMatrixTop(org.lwjgl.opengl.GL11.GL_PROJECTION, org.lwjgl.opengl.GL11.GL_PROJECTION_MATRIX,
                s.projectionTop);
        readMatrixTop(org.lwjgl.opengl.GL11.GL_TEXTURE, org.lwjgl.opengl.GL11.GL_TEXTURE_MATRIX,
                s.textureMatrixTop);
        org.lwjgl.opengl.GL11.glMatrixMode(s.matrixMode);
        for (int slot = 0; slot < SimulatedGlState.TRACKED_CAPS.length; slot++) {
            s.capEnabled[slot] = org.lwjgl.opengl.GL11.glIsEnabled(SimulatedGlState.TRACKED_CAPS[slot]);
        }
        s.blendEquation = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL14.GL_BLEND_EQUATION);
        s.alphaFunc = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_FUNC);
        s.alphaRef = org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_ALPHA_TEST_REF);
        s.arrayBufferBinding = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER_BINDING);
        s.elementArrayBufferBinding =
                org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        s.framebufferBinding = org.lwjgl.opengl.GL11.glGetInteger(
                org.lwjgl.opengl.EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
        return s;
    }

    private static void readMatrixTop(final int mode, final int pname, final float[] out) {
        org.lwjgl.opengl.GL11.glMatrixMode(mode);
        final FloatBuffer buf = ByteBuffer.allocateDirect(16 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        org.lwjgl.opengl.GL11.glGetFloat(pname, buf);
        buf.get(out);
    }
}
