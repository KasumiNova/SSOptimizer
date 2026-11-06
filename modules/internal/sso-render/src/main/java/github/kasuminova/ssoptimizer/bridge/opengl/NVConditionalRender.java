// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVConditionalRender {
    private NVConditionalRender() {
    }

    public static void glBeginConditionalRenderNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVConditionalRender.glBeginConditionalRenderNV(p0, p1));
    }

    public static void glEndConditionalRenderNV() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVConditionalRender.glEndConditionalRenderNV());
    }
}
