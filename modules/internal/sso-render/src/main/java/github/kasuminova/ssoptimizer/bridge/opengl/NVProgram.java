// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVProgram {
    private NVProgram() {
    }

    public static boolean glAreProgramsResidentNV(java.nio.IntBuffer p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVProgram.glAreProgramsResidentNV(p0, p1));
    }

    public static void glBindProgramNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVProgram.glBindProgramNV(p0, p1));
    }

    public static void glDeleteProgramsNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVProgram.glDeleteProgramsNV(p0));
    }

    public static void glDeleteProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.NVProgram.glDeleteProgramsNV(snapshot.asIntBuffer()));
    }

    public static int glGenProgramsNV() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVProgram.glGenProgramsNV());
    }

    public static void glGenProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.NVProgram.glGenProgramsNV(p0));
    }

    public static int glGetProgramNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVProgram.glGetProgramNV(p0, p1));
    }

    public static void glGetProgramNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVProgram.glGetProgramNV(p0, p1, buf)));
    }

    public static java.lang.String glGetProgramStringNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVProgram.glGetProgramStringNV(p0, p1));
    }

    public static void glGetProgramStringNV(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.NVProgram.glGetProgramStringNV(p0, p1, buf)));
    }

    public static int glGetProgramiNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVProgram.glGetProgramiNV(p0, p1));
    }

    public static boolean glIsProgramNV(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVProgram.glIsProgramNV(p0));
    }

    public static void glLoadProgramNV(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVProgram.glLoadProgramNV(p0, p1, p2));
    }

    public static void glLoadProgramNV(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVProgram.glLoadProgramNV(p0, p1, snapshot));
    }

    public static void glRequestResidentProgramsNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVProgram.glRequestResidentProgramsNV(p0));
    }

    public static void glRequestResidentProgramsNV(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.NVProgram.glRequestResidentProgramsNV(snapshot.asIntBuffer()));
    }
}
