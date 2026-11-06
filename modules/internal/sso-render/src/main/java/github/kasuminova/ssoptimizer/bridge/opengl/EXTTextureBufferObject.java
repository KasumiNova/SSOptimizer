package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTTextureBufferObject {
    private EXTTextureBufferObject() {
    }

    public static void glTexBufferEXT(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTTextureBufferObject.glTexBufferEXT(p0, p1, p2));
    }
}
