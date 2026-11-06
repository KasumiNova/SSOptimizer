// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVParameterBufferObject {
    private NVParameterBufferObject() {
    }

    public static void glProgramBufferParametersINV(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.NVParameterBufferObject.glProgramBufferParametersINV(p0, p1, p2, snapshot.asIntBuffer()));
    }

    public static void glProgramBufferParametersIuNV(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.NVParameterBufferObject.glProgramBufferParametersIuNV(p0, p1, p2, snapshot.asIntBuffer()));
    }

    public static void glProgramBufferParametersNV(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.NVParameterBufferObject.glProgramBufferParametersNV(p0, p1, p2, snapshot.asFloatBuffer()));
    }
}
