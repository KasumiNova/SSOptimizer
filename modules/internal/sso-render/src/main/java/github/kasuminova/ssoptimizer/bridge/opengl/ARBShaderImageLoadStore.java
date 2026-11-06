package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBShaderImageLoadStore {
    private ARBShaderImageLoadStore() {
    }

    public static void glBindImageTexture(int p0, int p1, int p2, boolean p3, int p4, int p5, int p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderImageLoadStore.glBindImageTexture(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glMemoryBarrier(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderImageLoadStore.glMemoryBarrier(p0));
    }
}
