package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTTextureArray {
    private EXTTextureArray() {
    }

    public static void glFramebufferTextureLayerEXT(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTextureArray.glFramebufferTextureLayerEXT(p0, p1, p2, p3, p4));
    }
}
