package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTPalettedTexture {
    private EXTPalettedTexture() {
    }

    public static void glColorSubTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorSubTableEXT(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glColorSubTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorSubTableEXT(p0, p1, p2, p3, p4, snapshot.asDoubleBuffer()));
    }

    public static void glColorSubTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.FloatBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorSubTableEXT(p0, p1, p2, p3, p4, snapshot.asFloatBuffer()));
    }

    public static void glColorSubTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.IntBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorSubTableEXT(p0, p1, p2, p3, p4, snapshot.asIntBuffer()));
    }

    public static void glColorSubTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.ShortBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorSubTableEXT(p0, p1, p2, p3, p4, snapshot.asShortBuffer()));
    }

    public static void glColorTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorTableEXT(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glColorTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.DoubleBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorTableEXT(p0, p1, p2, p3, p4, snapshot.asDoubleBuffer()));
    }

    public static void glColorTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.FloatBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorTableEXT(p0, p1, p2, p3, p4, snapshot.asFloatBuffer()));
    }

    public static void glColorTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.IntBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorTableEXT(p0, p1, p2, p3, p4, snapshot.asIntBuffer()));
    }

    public static void glColorTableEXT(int p0, int p1, int p2, int p3, int p4, java.nio.ShortBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.EXTPalettedTexture.glColorTableEXT(p0, p1, p2, p3, p4, snapshot.asShortBuffer()));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableEXT(int p0, int p1, int p2, java.nio.ByteBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p3, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableEXT(p0, p1, p2, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableEXT(int p0, int p1, int p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p3, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableEXT(p0, p1, p2, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableEXT(int p0, int p1, int p2, java.nio.FloatBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p3, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableEXT(p0, p1, p2, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableEXT(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p3, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableEXT(p0, p1, p2, buf)));
    }

    public static void glGetColorTableEXT(int p0, int p1, int p2, java.nio.ShortBuffer p3) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableEXT(p0, p1, p2, p3));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableParameterEXT(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableParameterEXT(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetColorTableParameterEXT(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.EXTPalettedTexture.glGetColorTableParameterEXT(p0, p1, buf)));
    }
}
