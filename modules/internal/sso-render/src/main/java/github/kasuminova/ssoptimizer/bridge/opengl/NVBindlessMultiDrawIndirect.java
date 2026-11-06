// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVBindlessMultiDrawIndirect {
    private NVBindlessMultiDrawIndirect() {
    }

    public static void glMultiDrawArraysIndirectBindlessNV(int p0, java.nio.ByteBuffer p1, int p2, int p3, int p4) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.NVBindlessMultiDrawIndirect.glMultiDrawArraysIndirectBindlessNV(p0, snapshot, p2, p3, p4));
    }

    public static void glMultiDrawArraysIndirectBindlessNV(int p0, long p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessMultiDrawIndirect.glMultiDrawArraysIndirectBindlessNV(p0, p1, p2, p3, p4));
    }

    public static void glMultiDrawElementsIndirectBindlessNV(int p0, int p1, java.nio.ByteBuffer p2, int p3, int p4, int p5) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.NVBindlessMultiDrawIndirect.glMultiDrawElementsIndirectBindlessNV(p0, p1, snapshot, p3, p4, p5));
    }

    public static void glMultiDrawElementsIndirectBindlessNV(int p0, int p1, long p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessMultiDrawIndirect.glMultiDrawElementsIndirectBindlessNV(p0, p1, p2, p3, p4, p5));
    }
}
