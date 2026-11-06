// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVDrawTexture {
    private NVDrawTexture() {
    }

    public static void glDrawTextureNV(int p0, int p1, float p2, float p3, float p4, float p5, float p6, float p7, float p8, float p9, float p10) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVDrawTexture.glDrawTextureNV(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }
}
