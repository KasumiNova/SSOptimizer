// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public final class NVPresentVideo {
    private NVPresentVideo() {
    }

    public static void glGetVideoNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVPresentVideo.glGetVideoNV(p0, p1, buf)));
    }

    public static void glGetVideoNV(int p0, int p1, java.nio.LongBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p2, 16, buf -> org.lwjgl.opengl.NVPresentVideo.glGetVideoNV(p0, p1, buf)));
    }

    public static long glGetVideoi64NV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVPresentVideo.glGetVideoi64NV(p0, p1));
    }

    public static int glGetVideoiNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVPresentVideo.glGetVideoiNV(p0, p1));
    }

    public static void glGetVideouNV(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.NVPresentVideo.glGetVideouNV(p0, p1, buf)));
    }

    public static void glGetVideouNV(int p0, int p1, java.nio.LongBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillLongs(p2, 16, buf -> org.lwjgl.opengl.NVPresentVideo.glGetVideouNV(p0, p1, buf)));
    }

    public static long glGetVideoui64NV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVPresentVideo.glGetVideoui64NV(p0, p1));
    }

    public static int glGetVideouiNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVPresentVideo.glGetVideouiNV(p0, p1));
    }

    public static void glPresentFrameDualFillNV(int p0, long p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10, int p11, int p12) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVPresentVideo.glPresentFrameDualFillNV(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12));
    }

    public static void glPresentFrameKeyedNV(int p0, long p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, int p10) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVPresentVideo.glPresentFrameKeyedNV(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }
}
