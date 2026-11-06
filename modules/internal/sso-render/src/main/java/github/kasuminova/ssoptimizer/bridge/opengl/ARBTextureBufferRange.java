package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTextureBufferRange {
    private ARBTextureBufferRange() {
    }

    public static void glTexBufferRange(int p0, int p1, int p2, long p3, long p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureBufferRange.glTexBufferRange(p0, p1, p2, p3, p4));
    }

    public static void glTextureBufferRangeEXT(int p0, int p1, int p2, int p3, long p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureBufferRange.glTextureBufferRangeEXT(p0, p1, p2, p3, p4, p5));
    }
}
