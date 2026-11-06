// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVTextureMultisample {
    private NVTextureMultisample() {
    }

    public static void glTexImage2DMultisampleCoverageNV(int p0, int p1, int p2, int p3, int p4, int p5, boolean p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTexImage2DMultisampleCoverageNV(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glTexImage3DMultisampleCoverageNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, boolean p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTexImage3DMultisampleCoverageNV(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glTextureImage2DMultisampleCoverageNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, boolean p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTextureImage2DMultisampleCoverageNV(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glTextureImage2DMultisampleNV(int p0, int p1, int p2, int p3, int p4, int p5, boolean p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTextureImage2DMultisampleNV(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glTextureImage3DMultisampleCoverageNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, boolean p8) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTextureImage3DMultisampleCoverageNV(p0, p1, p2, p3, p4, p5, p6, p7, p8));
    }

    public static void glTextureImage3DMultisampleNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, boolean p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVTextureMultisample.glTextureImage3DMultisampleNV(p0, p1, p2, p3, p4, p5, p6, p7));
    }
}
