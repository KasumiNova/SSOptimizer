package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBVertexShader {
    private ARBVertexShader() {
    }

    public static void glBindAttribLocationARB(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glBindAttribLocationARB(p0, p1, p2));
    }

    public static void glBindAttribLocationARB(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBVertexShader.glBindAttribLocationARB(p0, p1, snapshot));
    }

    public static void glDisableVertexAttribArrayARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glDisableVertexAttribArrayARB(p0));
    }

    public static void glEnableVertexAttribArrayARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glEnableVertexAttribArrayARB(p0));
    }

    public static java.lang.String glGetActiveAttribARB(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetActiveAttribARB(p0, p1, p2));
    }

    public static java.lang.String glGetActiveAttribARB(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetActiveAttribARB(p0, p1, p2, p3));
    }

    public static void glGetActiveAttribARB(int p0, int p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBVertexShader.glGetActiveAttribARB(p0, p1, p2, p3, p4, p5));
    }

    public static int glGetActiveAttribSizeARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetActiveAttribSizeARB(p0, p1));
    }

    public static int glGetActiveAttribTypeARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetActiveAttribTypeARB(p0, p1));
    }

    public static int glGetAttribLocationARB(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetAttribLocationARB(p0, p1));
    }

    public static int glGetAttribLocationARB(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetAttribLocationARB(p0, p1));
    }

    public static void glGetVertexAttribARB(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p2, 16, buf -> org.lwjgl.opengl.ARBVertexShader.glGetVertexAttribARB(p0, p1, buf)));
    }

    public static void glGetVertexAttribARB(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBVertexShader.glGetVertexAttribARB(p0, p1, buf)));
    }

    public static void glGetVertexAttribARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBVertexShader.glGetVertexAttribARB(p0, p1, buf)));
    }

    public static java.nio.ByteBuffer glGetVertexAttribPointerARB(int p0, int p1, long p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBVertexShader.glGetVertexAttribPointerARB(p0, p1, p2));
    }

    public static void glVertexAttrib1dARB(int p0, double p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib1dARB(p0, p1));
    }

    public static void glVertexAttrib1fARB(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib1fARB(p0, p1));
    }

    public static void glVertexAttrib1sARB(int p0, short p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib1sARB(p0, p1));
    }

    public static void glVertexAttrib2dARB(int p0, double p1, double p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib2dARB(p0, p1, p2));
    }

    public static void glVertexAttrib2fARB(int p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib2fARB(p0, p1, p2));
    }

    public static void glVertexAttrib2sARB(int p0, short p1, short p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib2sARB(p0, p1, p2));
    }

    public static void glVertexAttrib3dARB(int p0, double p1, double p2, double p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib3dARB(p0, p1, p2, p3));
    }

    public static void glVertexAttrib3fARB(int p0, float p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib3fARB(p0, p1, p2, p3));
    }

    public static void glVertexAttrib3sARB(int p0, short p1, short p2, short p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib3sARB(p0, p1, p2, p3));
    }

    public static void glVertexAttrib4NubARB(int p0, byte p1, byte p2, byte p3, byte p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib4NubARB(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4dARB(int p0, double p1, double p2, double p3, double p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib4dARB(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4fARB(int p0, float p1, float p2, float p3, float p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib4fARB(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4sARB(int p0, short p1, short p2, short p3, short p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttrib4sARB(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, boolean p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, snapshot.asDoubleBuffer()));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, boolean p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, snapshot.asFloatBuffer()));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.IntBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, p4, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.ShortBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, p4, snapshot.asShortBuffer()));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, int p2, boolean p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glVertexAttribPointerARB(int p0, int p1, int p2, boolean p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBVertexShader.glVertexAttribPointerARB(p0, p1, p2, p3, p4, p5));
    }
}
