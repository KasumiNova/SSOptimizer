package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTransformFeedback3 {
    private ARBTransformFeedback3() {
    }

    public static void glBeginQueryIndexed(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTransformFeedback3.glBeginQueryIndexed(p0, p1, p2));
    }

    public static void glDrawTransformFeedbackStream(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTransformFeedback3.glDrawTransformFeedbackStream(p0, p1, p2));
    }

    public static void glEndQueryIndexed(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTransformFeedback3.glEndQueryIndexed(p0, p1));
    }

    public static void glGetQueryIndexed(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.ARBTransformFeedback3.glGetQueryIndexed(p0, p1, p2, buf)));
    }

    public static int glGetQueryIndexedi(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBTransformFeedback3.glGetQueryIndexedi(p0, p1, p2));
    }
}
