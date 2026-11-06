// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVBlendEquationAdvanced {
    private NVBlendEquationAdvanced() {
    }

    public static void glBlendBarrierNV() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBlendEquationAdvanced.glBlendBarrierNV());
    }

    public static void glBlendParameteriNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBlendEquationAdvanced.glBlendParameteriNV(p0, p1));
    }
}
