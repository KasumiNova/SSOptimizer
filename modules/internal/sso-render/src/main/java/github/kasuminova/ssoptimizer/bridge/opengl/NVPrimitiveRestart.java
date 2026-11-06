// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVPrimitiveRestart {
    private NVPrimitiveRestart() {
    }

    public static void glPrimitiveRestartIndexNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVPrimitiveRestart.glPrimitiveRestartIndexNV(p0));
    }

    public static void glPrimitiveRestartNV() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVPrimitiveRestart.glPrimitiveRestartNV());
    }
}
