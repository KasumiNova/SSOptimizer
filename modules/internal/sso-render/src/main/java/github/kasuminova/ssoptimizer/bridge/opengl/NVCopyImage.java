// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVCopyImage {
    private NVCopyImage() {
    }

    public static void glCopyImageSubDataNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11, int p12, int p13, int p14) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVCopyImage.glCopyImageSubDataNV(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14));
    }
}
