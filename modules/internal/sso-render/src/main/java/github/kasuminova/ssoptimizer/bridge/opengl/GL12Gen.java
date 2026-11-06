package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL12Gen {
    protected GL12Gen() {
    }

    public static void glCopyTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glCopyTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8));
    }

    public static void glDrawRangeElements(int p0, int p1, int p2, java.nio.ByteBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL12.glDrawRangeElements(p0, p1, p2, snapshot));
    }

    public static void glDrawRangeElements(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL12.glDrawRangeElements(p0, p1, p2, snapshot.asIntBuffer()));
    }

    public static void glDrawRangeElements(int p0, int p1, int p2, java.nio.ShortBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL12.glDrawRangeElements(p0, p1, p2, snapshot.asShortBuffer()));
    }

    public static void glDrawRangeElements(int p0, int p1, int p2, int p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glDrawRangeElements(p0, p1, p2, p3, p4, p5));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.ByteBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.DoubleBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot.asDoubleBuffer()));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.FloatBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot.asFloatBuffer()));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.IntBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot.asIntBuffer()));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, java.nio.ShortBuffer p9) {
        BridgeSupport.enqueueSnapshot(p9, snapshot -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, snapshot.asShortBuffer()));
    }

    public static void glTexImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, long p9) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.ByteBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.DoubleBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asDoubleBuffer()));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.FloatBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asFloatBuffer()));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.IntBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asIntBuffer()));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.ShortBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asShortBuffer()));
    }

    public static void glTexSubImage3D(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, long p10) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL12.glTexSubImage3D(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }
}
