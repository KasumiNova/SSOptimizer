package github.kasuminova.ssoptimizer.bridge.opengl;

public final class EXTDrawInstanced {
    private EXTDrawInstanced() {
    }

    public static void glDrawArraysInstancedEXT(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTDrawInstanced.glDrawArraysInstancedEXT(p0, p1, p2, p3));
    }

    public static void glDrawElementsInstancedEXT(int p0, java.nio.ByteBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.EXTDrawInstanced.glDrawElementsInstancedEXT(p0, snapshot, p2));
    }

    public static void glDrawElementsInstancedEXT(int p0, java.nio.IntBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.EXTDrawInstanced.glDrawElementsInstancedEXT(p0, snapshot.asIntBuffer(), p2));
    }

    public static void glDrawElementsInstancedEXT(int p0, java.nio.ShortBuffer p1, int p2) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.EXTDrawInstanced.glDrawElementsInstancedEXT(p0, snapshot.asShortBuffer(), p2));
    }

    public static void glDrawElementsInstancedEXT(int p0, int p1, int p2, long p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.EXTDrawInstanced.glDrawElementsInstancedEXT(p0, p1, p2, p3, p4));
    }
}
