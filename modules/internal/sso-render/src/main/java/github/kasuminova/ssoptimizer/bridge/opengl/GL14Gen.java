package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL14Gen {
    protected GL14Gen() {
    }

    public static void glBlendColor(float p0, float p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendColor(p0, p1, p2, p3));
    }

    public static void glBlendEquation(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendEquation(p0));
    }

    public static void glBlendFuncSeparate(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendFuncSeparate(p0, p1, p2, p3));
    }

    public static void glFogCoordPointer(int p0, java.nio.DoubleBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL14.glFogCoordPointer(p0, snapshot.asDoubleBuffer()));
    }

    public static void glFogCoordPointer(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL14.glFogCoordPointer(p0, snapshot.asFloatBuffer()));
    }

    public static void glFogCoordPointer(int p0, int p1, long p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glFogCoordPointer(p0, p1, p2));
    }

    public static void glFogCoordd(double p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glFogCoordd(p0));
    }

    public static void glFogCoordf(float p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glFogCoordf(p0));
    }

    public static void glMultiDrawArrays(int p0, java.nio.IntBuffer p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL14.glMultiDrawArrays(p0, p1, p2));
    }

    public static void glPointParameter(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL14.glPointParameter(p0, snapshot.asFloatBuffer()));
    }

    public static void glPointParameter(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL14.glPointParameter(p0, snapshot.asIntBuffer()));
    }

    public static void glPointParameterf(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glPointParameterf(p0, p1));
    }

    public static void glPointParameteri(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glPointParameteri(p0, p1));
    }

    public static void glSecondaryColor3b(byte p0, byte p1, byte p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glSecondaryColor3b(p0, p1, p2));
    }

    public static void glSecondaryColor3d(double p0, double p1, double p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glSecondaryColor3d(p0, p1, p2));
    }

    public static void glSecondaryColor3f(float p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glSecondaryColor3f(p0, p1, p2));
    }

    public static void glSecondaryColor3ub(byte p0, byte p1, byte p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glSecondaryColor3ub(p0, p1, p2));
    }

    public static void glSecondaryColorPointer(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL14.glSecondaryColorPointer(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glSecondaryColorPointer(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL14.glSecondaryColorPointer(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glSecondaryColorPointer(int p0, boolean p1, int p2, java.nio.ByteBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.GL14.glSecondaryColorPointer(p0, p1, p2, snapshot));
    }

    public static void glSecondaryColorPointer(int p0, int p1, int p2, long p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glSecondaryColorPointer(p0, p1, p2, p3));
    }

    public static void glWindowPos2d(double p0, double p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos2d(p0, p1));
    }

    public static void glWindowPos2f(float p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos2f(p0, p1));
    }

    public static void glWindowPos2i(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos2i(p0, p1));
    }

    public static void glWindowPos3d(double p0, double p1, double p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos3d(p0, p1, p2));
    }

    public static void glWindowPos3f(float p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos3f(p0, p1, p2));
    }

    public static void glWindowPos3i(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glWindowPos3i(p0, p1, p2));
    }
}
