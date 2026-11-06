package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTextureMultisample {
    private ARBTextureMultisample() {
    }

    public static void glGetMultisample(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBTextureMultisample.glGetMultisample(p0, p1, buf)));
    }

    public static void glSampleMaski(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureMultisample.glSampleMaski(p0, p1));
    }

    public static void glTexImage2DMultisample(int p0, int p1, int p2, int p3, int p4, boolean p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureMultisample.glTexImage2DMultisample(p0, p1, p2, p3, p4, p5));
    }

    public static void glTexImage3DMultisample(int p0, int p1, int p2, int p3, int p4, int p5, boolean p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureMultisample.glTexImage3DMultisample(p0, p1, p2, p3, p4, p5, p6));
    }
}
