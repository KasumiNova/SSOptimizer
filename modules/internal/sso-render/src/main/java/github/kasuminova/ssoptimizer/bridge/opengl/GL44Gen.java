package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL44Gen {
    protected GL44Gen() {
    }

    public static void glBindBuffersBase(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL44.glBindBuffersBase(p0, p1, p2, snapshot.asIntBuffer()));
    }

    public static void glBindBuffersRange(int p0, int p1, int p2, java.nio.IntBuffer p3, org.lwjgl.PointerBuffer p4, org.lwjgl.PointerBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL44.glBindBuffersRange(p0, p1, p2, p3, p4, p5));
    }

    public static void glBindImageTextures(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL44.glBindImageTextures(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glBindSamplers(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL44.glBindSamplers(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glBindTextures(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL44.glBindTextures(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glBindVertexBuffers(int p0, int p1, java.nio.IntBuffer p2, org.lwjgl.PointerBuffer p3, java.nio.IntBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL44.glBindVertexBuffers(p0, p1, p2, p3, p4));
    }

    public static void glBufferStorage(int p0, java.nio.ByteBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL44.glBufferStorage(p0, snapshot, p2));
    }

    public static void glBufferStorage(int p0, java.nio.DoubleBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL44.glBufferStorage(p0, snapshot.asDoubleBuffer(), p2));
    }

    public static void glBufferStorage(int p0, java.nio.FloatBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL44.glBufferStorage(p0, snapshot.asFloatBuffer(), p2));
    }

    public static void glBufferStorage(int p0, java.nio.IntBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL44.glBufferStorage(p0, snapshot.asIntBuffer(), p2));
    }

    // LongBuffer 无快照重载，阻塞期间直传原缓冲区。
    public static void glBufferStorage(int p0, java.nio.LongBuffer p1, int p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL44.glBufferStorage(p0, p1, p2));
    }

    public static void glBufferStorage(int p0, java.nio.ShortBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL44.glBufferStorage(p0, snapshot.asShortBuffer(), p2));
    }

    public static void glBufferStorage(int p0, long p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL44.glBufferStorage(p0, p1, p2));
    }

    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.ByteBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, snapshot));
    }

    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, snapshot.asDoubleBuffer()));
    }

    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, snapshot.asFloatBuffer()));
    }

    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.IntBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, snapshot.asIntBuffer()));
    }

    // LongBuffer 无快照重载，阻塞期间直传原缓冲区。
    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.LongBuffer p4) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, p4));
    }

    public static void glClearTexImage(int p0, int p1, int p2, int p3, java.nio.ShortBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL44.glClearTexImage(p0, p1, p2, p3, snapshot.asShortBuffer()));
    }

    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.ByteBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot));
    }

    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.DoubleBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asDoubleBuffer()));
    }

    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.FloatBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asFloatBuffer()));
    }

    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.IntBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asIntBuffer()));
    }

    // LongBuffer 无快照重载，阻塞期间直传原缓冲区。
    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.LongBuffer p10) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10));
    }

    public static void glClearTexSubImage(int p0, int p1, int p2, int p3, int p4, int p5, int p6, int p7, int p8, int p9, java.nio.ShortBuffer p10) {
        BridgeSupport.enqueueSnapshot(p10, snapshot -> org.lwjgl.opengl.GL44.glClearTexSubImage(p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, snapshot.asShortBuffer()));
    }
}
