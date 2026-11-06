package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBTextureCompression {
    private ARBTextureCompression() {
    }

    public static void glCompressedTexImage1DARB(int p0, int p1, int p2, int p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage1DARB(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glCompressedTexImage1DARB(int p0, int p1, int p2, int p3, int p4, int p5, long p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage1DARB(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glCompressedTexImage2DARB(int p0, int p1, int p2, int p3, int p4, int p5, java.nio.ByteBuffer p6) {
        BridgeSupport.enqueueSnapshot(p6, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage2DARB(p0, p1, p2, p3, p4, p5, snapshot));
    }

    public static void glCompressedTexImage2DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, long p7) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage2DARB(p0, p1, p2, p3, p4, p5, p6, p7));
    }

    public static void glCompressedTexImage3DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, java.nio.ByteBuffer p7) {
        BridgeSupport.enqueueSnapshot(p7, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage3DARB(p0, p1, p2, p3, p4, p5, p6, snapshot));
    }

    public static void glCompressedTexImage3DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, long p8) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexImage3DARB(p0, p1, p2, p3, p4, p5, p6, p7, p8));
    }

    public static void glCompressedTexSubImage1DARB(int p0, int p1, int p2, int p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage1DARB(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glCompressedTexSubImage1DARB(int p0, int p1, int p2, int p3, int p4, int p5, long p6) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage1DARB(p0, p1, p2, p3, p4, p5, p6));
    }

    public static void glCompressedTexSubImage2DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, java.nio.ByteBuffer p7) {
        BridgeSupport.enqueueSnapshot(p7, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage2DARB(p0, p1, p2, p3, p4, p5, p6, snapshot));
    }

    public static void glCompressedTexSubImage2DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, long p8) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage2DARB(p0, p1, p2, p3, p4, p5, p6, p7, p8));
    }

    public static void glCompressedTexSubImage3DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.ByteBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage3DARB(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot));
    }

    public static void glCompressedTexSubImage3DARB(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, long p10) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glCompressedTexSubImage3DARB(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }

    public static void glGetCompressedTexImageARB(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.ARBTextureCompression.glGetCompressedTexImageARB(p0, p1, buf)));
    }

    public static void glGetCompressedTexImageARB(int p0, int p1, long p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureCompression.glGetCompressedTexImageARB(p0, p1, p2));
    }
}
