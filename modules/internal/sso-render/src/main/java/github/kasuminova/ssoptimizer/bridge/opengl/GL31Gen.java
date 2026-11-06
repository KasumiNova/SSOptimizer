package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL31Gen {
    protected GL31Gen() {
    }

    public static void glCopyBufferSubData(int p0, int p1, long p2, long p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glCopyBufferSubData(p0, p1, p2, p3, p4));
    }

    public static void glDrawArraysInstanced(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glDrawArraysInstanced(p0, p1, p2, p3));
    }

    public static void glDrawElementsInstanced(int p0, java.nio.ByteBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL31.glDrawElementsInstanced(p0, snapshot, p2));
    }

    public static void glDrawElementsInstanced(int p0, java.nio.IntBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL31.glDrawElementsInstanced(p0, snapshot.asIntBuffer(), p2));
    }

    public static void glDrawElementsInstanced(int p0, java.nio.ShortBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL31.glDrawElementsInstanced(p0, snapshot.asShortBuffer(), p2));
    }

    public static void glDrawElementsInstanced(int p0, int p1, int p2, long p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glDrawElementsInstanced(p0, p1, p2, p3, p4));
    }

    public static int glGetActiveUniformBlock(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniformBlock(p0, p1, p2));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetActiveUniformBlock(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.GL31.glGetActiveUniformBlock(p0, p1, p2, buf)));
    }

    public static java.lang.String glGetActiveUniformBlockName(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniformBlockName(p0, p1, p2));
    }

    public static void glGetActiveUniformBlockName(int p0, int p1, java.nio.IntBuffer p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL31.glGetActiveUniformBlockName(p0, p1, p2, p3));
    }

    public static int glGetActiveUniformBlocki(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniformBlocki(p0, p1, p2));
    }

    public static java.lang.String glGetActiveUniformName(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniformName(p0, p1, p2));
    }

    public static void glGetActiveUniformName(int p0, int p1, java.nio.IntBuffer p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL31.glGetActiveUniformName(p0, p1, p2, p3));
    }

    public static int glGetActiveUniforms(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniforms(p0, p1, p2));
    }

    public static void glGetActiveUniforms(int p0, java.nio.IntBuffer p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL31.glGetActiveUniforms(p0, p1, p2, p3));
    }

    public static int glGetActiveUniformsi(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetActiveUniformsi(p0, p1, p2));
    }

    public static int glGetUniformBlockIndex(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(p0, p1));
    }

    public static int glGetUniformBlockIndex(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(p0, p1));
    }

    public static void glGetUniformIndices(int p0, java.lang.CharSequence[] p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL31.glGetUniformIndices(p0, p1, p2));
    }

    public static void glGetUniformIndices(int p0, java.nio.ByteBuffer p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL31.glGetUniformIndices(p0, p1, p2));
    }

    public static void glPrimitiveRestartIndex(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glPrimitiveRestartIndex(p0));
    }

    public static void glTexBuffer(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glTexBuffer(p0, p1, p2));
    }

    public static void glUniformBlockBinding(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glUniformBlockBinding(p0, p1, p2));
    }
}
