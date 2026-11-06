// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVEvaluators {
    private NVEvaluators() {
    }

    public static void glEvalMapsNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVEvaluators.glEvalMapsNV(p0, p1));
    }

    public static void glGetMapAttribParameterNV(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p3, 16, buf -> org.lwjgl.opengl.NVEvaluators.glGetMapAttribParameterNV(p0, p1, p2, buf)));
    }

    public static void glGetMapAttribParameterNV(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.NVEvaluators.glGetMapAttribParameterNV(p0, p1, p2, buf)));
    }

    public static void glGetMapControlPointsNV(int p0, int p1, int p2, int p3, int p4, boolean p5, java.nio.FloatBuffer p6) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p6, 16, buf -> org.lwjgl.opengl.NVEvaluators.glGetMapControlPointsNV(p0, p1, p2, p3, p4, p5, buf)));
    }

    public static void glGetMapParameterNV(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.NVEvaluators.glGetMapParameterNV(p0, p1, buf)));
    }

    public static void glGetMapParameterNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVEvaluators.glGetMapParameterNV(p0, p1, buf)));
    }

    public static void glMapControlPointsNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, boolean p7, java.nio.FloatBuffer p8) {
        BridgeSupport.enqueueSnapshot(p8, snapshot -> org.lwjgl.opengl.NVEvaluators.glMapControlPointsNV(p0, p1, p2, p3, p4, p5, p6, p7, snapshot.asFloatBuffer()));
    }

    public static void glMapParameterNV(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVEvaluators.glMapParameterNV(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glMapParameterNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVEvaluators.glMapParameterNV(p0, p1, snapshot.asIntBuffer()));
    }
}
