package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTTransformFeedback {
    private EXTTransformFeedback() {
    }

    public static void glBeginTransformFeedbackEXT(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glBeginTransformFeedbackEXT(p0));
    }

    public static void glBindBufferBaseEXT(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glBindBufferBaseEXT(p0, p1, p2));
    }

    public static void glBindBufferOffsetEXT(int p0, int p1, int p2, long p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glBindBufferOffsetEXT(p0, p1, p2, p3));
    }

    public static void glBindBufferRangeEXT(int p0, int p1, int p2, long p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glBindBufferRangeEXT(p0, p1, p2, p3, p4));
    }

    public static void glEndTransformFeedbackEXT() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glEndTransformFeedbackEXT());
    }

    public static java.lang.String glGetTransformFeedbackVaryingEXT(int p0, int p1, int p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.EXTTransformFeedback.glGetTransformFeedbackVaryingEXT(p0, p1, p2, p3, p4));
    }

    public static void glGetTransformFeedbackVaryingEXT(int p0, int p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.EXTTransformFeedback.glGetTransformFeedbackVaryingEXT(p0, p1, p2, p3, p4, p5));
    }

    public static void glTransformFeedbackVaryingsEXT(int p0, java.lang.CharSequence[] p1, int p2) {
        final java.lang.CharSequence[] copy = p1.clone();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTransformFeedback.glTransformFeedbackVaryingsEXT(p0, copy, p2));
    }

    public static void glTransformFeedbackVaryingsEXT(int p0, int p1, java.nio.ByteBuffer p2, int p3) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.EXTTransformFeedback.glTransformFeedbackVaryingsEXT(p0, p1, snapshot, p3));
    }
}
