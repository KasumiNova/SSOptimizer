// 由 tools/gen_gl_bridge.py 生成，请勿手动修改。
package github.kasuminova.ssoptimizer.bridge.opengl;

public class NVBindlessTextureGen {
    protected NVBindlessTextureGen() {
    }

    public static long glGetImageHandleNV(int p0, int p1, boolean p2, int p3, int p4) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glGetImageHandleNV(p0, p1, p2, p3, p4));
    }

    public static long glGetTextureHandleNV(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glGetTextureHandleNV(p0));
    }

    public static long glGetTextureSamplerHandleNV(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glGetTextureSamplerHandleNV(p0, p1));
    }

    public static boolean glIsImageHandleResidentNV(long p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glIsImageHandleResidentNV(p0));
    }

    public static boolean glIsTextureHandleResidentNV(long p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.NVBindlessTexture.glIsTextureHandleResidentNV(p0));
    }

    public static void glMakeImageHandleNonResidentNV(long p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeImageHandleNonResidentNV(p0));
    }

    public static void glMakeImageHandleResidentNV(long p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeImageHandleResidentNV(p0, p1));
    }

    public static void glMakeTextureHandleNonResidentNV(long p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeTextureHandleNonResidentNV(p0));
    }

    public static void glMakeTextureHandleResidentNV(long p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glMakeTextureHandleResidentNV(p0));
    }

    public static void glProgramUniformHandleuNV(int p0, int p1, java.nio.LongBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.NVBindlessTexture.glProgramUniformHandleuNV(p0, p1, p2));
    }

    public static void glProgramUniformHandleui64NV(int p0, int p1, long p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glProgramUniformHandleui64NV(p0, p1, p2));
    }

    public static void glUniformHandleuNV(int p0, java.nio.LongBuffer p1) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.NVBindlessTexture.glUniformHandleuNV(p0, p1));
    }

    public static void glUniformHandleui64NV(int p0, long p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.NVBindlessTexture.glUniformHandleui64NV(p0, p1));
    }
}
