// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVFragmentProgram {
    private NVFragmentProgram() {
    }

    public static boolean glAreProgramsResidentNV(java.nio.IntBuffer p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFragmentProgram.glAreProgramsResidentNV(p0, p1));
    }

    public static void glBindProgramNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFragmentProgram.glBindProgramNV(p0, p1));
    }

    public static void glDeleteProgramsNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFragmentProgram.glDeleteProgramsNV(p0));
    }

    public static void glDeleteProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.NVFragmentProgram.glDeleteProgramsNV(snapshot.asIntBuffer()));
    }

    public static int glGenProgramsNV() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVFragmentProgram.glGenProgramsNV());
    }

    public static void glGenProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.NVFragmentProgram.glGenProgramsNV(p0));
    }

    public static int glGetProgramNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramNV(p0, p1));
    }

    public static void glGetProgramNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramNV(p0, p1, buf)));
    }

    public static void glGetProgramNamedParameterNV(int p0, java.nio.ByteBuffer p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramNamedParameterNV(p0, p1, p2));
    }

    public static void glGetProgramNamedParameterNV(int p0, java.nio.ByteBuffer p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramNamedParameterNV(p0, p1, p2));
    }

    public static java.lang.String glGetProgramStringNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramStringNV(p0, p1));
    }

    public static void glGetProgramStringNV(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramStringNV(p0, p1, buf)));
    }

    public static int glGetProgramiNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFragmentProgram.glGetProgramiNV(p0, p1));
    }

    public static boolean glIsProgramNV(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFragmentProgram.glIsProgramNV(p0));
    }

    public static void glLoadProgramNV(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFragmentProgram.glLoadProgramNV(p0, p1, p2));
    }

    public static void glLoadProgramNV(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVFragmentProgram.glLoadProgramNV(p0, p1, snapshot));
    }

    public static void glProgramNamedParameter4dNV(int p0, java.nio.ByteBuffer p1, double p2, double p3, double p4, double p5) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVFragmentProgram.glProgramNamedParameter4dNV(p0, snapshot, p2, p3, p4, p5));
    }

    public static void glProgramNamedParameter4fNV(int p0, java.nio.ByteBuffer p1, float p2, float p3, float p4, float p5) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVFragmentProgram.glProgramNamedParameter4fNV(p0, snapshot, p2, p3, p4, p5));
    }

    public static void glRequestResidentProgramsNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFragmentProgram.glRequestResidentProgramsNV(p0));
    }

    public static void glRequestResidentProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.NVFragmentProgram.glRequestResidentProgramsNV(snapshot.asIntBuffer()));
    }
}
