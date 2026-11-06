// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVRegisterCombiners2 {
    private NVRegisterCombiners2() {
    }

    public static void glCombinerStageParameterNV(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVRegisterCombiners2.glCombinerStageParameterNV(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glGetCombinerStageParameterNV(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners2.glGetCombinerStageParameterNV(p0, p1, buf)));
    }
}
