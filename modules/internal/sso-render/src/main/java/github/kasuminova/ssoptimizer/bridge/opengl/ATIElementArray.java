package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ATIElementArray {
    private ATIElementArray() {
    }

    public static void glDrawElementArrayATI(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATIElementArray.glDrawElementArrayATI(p0, p1));
    }

    public static void glDrawRangeElementArrayATI(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ATIElementArray.glDrawRangeElementArrayATI(p0, p1, p2, p3));
    }

    public static void glElementPointerATI(java.nio.ByteBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.ATIElementArray.glElementPointerATI(snapshot));
    }

    public static void glElementPointerATI(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.ATIElementArray.glElementPointerATI(snapshot.asIntBuffer()));
    }

    public static void glElementPointerATI(java.nio.ShortBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.ATIElementArray.glElementPointerATI(snapshot.asShortBuffer()));
    }
}
