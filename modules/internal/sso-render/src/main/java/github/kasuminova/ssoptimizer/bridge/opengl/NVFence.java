// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVFence {
    private NVFence() {
    }

    public static void glDeleteFencesNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFence.glDeleteFencesNV(p0));
    }

    public static void glDeleteFencesNV(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.NVFence.glDeleteFencesNV(snapshot.asIntBuffer()));
    }

    public static void glFinishFenceNV(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFence.glFinishFenceNV(p0));
    }

    public static int glGenFencesNV() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.NVFence.glGenFencesNV());
    }

    public static void glGenFencesNV(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.NVFence.glGenFencesNV(p0));
    }

    public static void glGetFenceivNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVFence.glGetFenceivNV(p0, p1, buf)));
    }

    public static boolean glIsFenceNV(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFence.glIsFenceNV(p0));
    }

    public static void glSetFenceNV(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVFence.glSetFenceNV(p0, p1));
    }

    public static boolean glTestFenceNV(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVFence.glTestFenceNV(p0));
    }
}
