// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVRegisterCombiners {
    private NVRegisterCombiners() {
    }

    public static void glCombinerInputNV(int p0, int p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerInputNV(p0, p1, p2, p3, p4, p5));
    }

    public static void glCombinerOutputNV(int p0, int p1, int p2, int p3, int p4, int p5, int p6, boolean p7, boolean p8, boolean p9) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerOutputNV(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9));
    }

    public static void glCombinerParameterNV(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerParameterNV(p0, snapshot.asFloatBuffer()));
    }

    public static void glCombinerParameterNV(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerParameterNV(p0, snapshot.asIntBuffer()));
    }

    public static void glCombinerParameterfNV(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerParameterfNV(p0, p1));
    }

    public static void glCombinerParameteriNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVRegisterCombiners.glCombinerParameteriNV(p0, p1));
    }

    public static void glFinalCombinerInputNV(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVRegisterCombiners.glFinalCombinerInputNV(p0, p1, p2, p3));
    }

    public static void glGetCombinerInputParameterNV(int p0, int p1, int p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p4, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerInputParameterNV(p0, p1, p2, p3, buf)));
    }

    public static void glGetCombinerInputParameterNV(int p0, int p1, int p2, int p3, java.nio.IntBuffer p4) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p4, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerInputParameterNV(p0, p1, p2, p3, buf)));
    }

    public static float glGetCombinerInputParameterfNV(int p0, int p1, int p2, int p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerInputParameterfNV(p0, p1, p2, p3));
    }

    public static int glGetCombinerInputParameteriNV(int p0, int p1, int p2, int p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerInputParameteriNV(p0, p1, p2, p3));
    }

    public static void glGetCombinerOutputParameterNV(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p3, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerOutputParameterNV(p0, p1, p2, buf)));
    }

    public static void glGetCombinerOutputParameterNV(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerOutputParameterNV(p0, p1, p2, buf)));
    }

    public static float glGetCombinerOutputParameterfNV(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerOutputParameterfNV(p0, p1, p2));
    }

    public static int glGetCombinerOutputParameteriNV(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetCombinerOutputParameteriNV(p0, p1, p2));
    }

    public static void glGetFinalCombinerInputParameterNV(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetFinalCombinerInputParameterNV(p0, p1, buf)));
    }

    public static void glGetFinalCombinerInputParameterNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVRegisterCombiners.glGetFinalCombinerInputParameterNV(p0, p1, buf)));
    }

    public static float glGetFinalCombinerInputParameterfNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetFinalCombinerInputParameterfNV(p0, p1));
    }

    public static int glGetFinalCombinerInputParameteriNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVRegisterCombiners.glGetFinalCombinerInputParameteriNV(p0, p1));
    }
}
