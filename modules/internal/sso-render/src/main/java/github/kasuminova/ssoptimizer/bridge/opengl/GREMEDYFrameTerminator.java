// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class GREMEDYFrameTerminator {
    private GREMEDYFrameTerminator() {
    }

    public static void glFrameTerminatorGREMEDY() {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GREMEDYFrameTerminator.glFrameTerminatorGREMEDY());
    }
}
