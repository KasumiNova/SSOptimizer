package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTShaderImageLoadStore {
    private EXTShaderImageLoadStore() {
    }

    public static void glBindImageTextureEXT(int p0, int p1, int p2, boolean p3, int p4, int p5, int p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTShaderImageLoadStore.glBindImageTextureEXT(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glMemoryBarrierEXT(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTShaderImageLoadStore.glMemoryBarrierEXT(p0));
    }
}
