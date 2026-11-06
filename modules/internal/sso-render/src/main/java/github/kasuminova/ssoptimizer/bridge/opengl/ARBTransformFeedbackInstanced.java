package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTransformFeedbackInstanced {
    private ARBTransformFeedbackInstanced() {
    }

    public static void glDrawTransformFeedbackInstanced(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTransformFeedbackInstanced.glDrawTransformFeedbackInstanced(p0, p1, p2));
    }

    public static void glDrawTransformFeedbackStreamInstanced(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTransformFeedbackInstanced.glDrawTransformFeedbackStreamInstanced(p0, p1, p2, p3));
    }
}
