package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBUniformBufferObject {
    private ARBUniformBufferObject() {
    }

    public static void glBindBufferBase(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferBase(p0, p1, p2));
    }

    public static void glBindBufferRange(int p0, int p1, int p2, long p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBUniformBufferObject.glBindBufferRange(p0, p1, p2, p3, p4));
    }

    public static int glGetActiveUniformBlock(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformBlock(p0, p1, p2));
    }

    public static void glGetActiveUniformBlock(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformBlock(p0, p1, p2, buf)));
    }

    public static java.lang.String glGetActiveUniformBlockName(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformBlockName(p0, p1, p2));
    }

    public static void glGetActiveUniformBlockName(int p0, int p1, java.nio.IntBuffer p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformBlockName(p0, p1, p2, p3));
    }

    public static int glGetActiveUniformBlocki(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformBlocki(p0, p1, p2));
    }

    public static java.lang.String glGetActiveUniformName(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformName(p0, p1, p2));
    }

    public static void glGetActiveUniformName(int p0, int p1, java.nio.IntBuffer p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformName(p0, p1, p2, p3));
    }

    public static int glGetActiveUniforms(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniforms(p0, p1, p2));
    }

    public static void glGetActiveUniforms(int p0, java.nio.IntBuffer p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniforms(p0, p1, p2, p3));
    }

    public static int glGetActiveUniformsi(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetActiveUniformsi(p0, p1, p2));
    }

    public static int glGetInteger(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetInteger(p0, p1));
    }

    public static void glGetInteger(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBUniformBufferObject.glGetInteger(p0, p1, buf)));
    }

    public static int glGetUniformBlockIndex(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetUniformBlockIndex(p0, p1));
    }

    public static int glGetUniformBlockIndex(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetUniformBlockIndex(p0, p1));
    }

    public static void glGetUniformIndices(int p0, java.lang.CharSequence[] p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetUniformIndices(p0, p1, p2));
    }

    public static void glGetUniformIndices(int p0, java.nio.ByteBuffer p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBUniformBufferObject.glGetUniformIndices(p0, p1, p2));
    }

    public static void glUniformBlockBinding(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBUniformBufferObject.glUniformBlockBinding(p0, p1, p2));
    }
}
