package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBSamplerObjects {
    private ARBSamplerObjects() {
    }

    public static void glBindSampler(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSamplerObjects.glBindSampler(p0, p1));
    }

    public static void glDeleteSamplers(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSamplerObjects.glDeleteSamplers(p0));
    }

    public static void glDeleteSamplers(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.ARBSamplerObjects.glDeleteSamplers(snapshot.asIntBuffer()));
    }

    public static int glGenSamplers() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSamplerObjects.glGenSamplers());
    }

    public static void glGenSamplers(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBSamplerObjects.glGenSamplers(p0));
    }

    public static void glGetSamplerParameter(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameter(p0, p1, buf)));
    }

    public static void glGetSamplerParameter(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameter(p0, p1, buf)));
    }

    public static void glGetSamplerParameterI(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameterI(p0, p1, buf)));
    }

    public static int glGetSamplerParameterIi(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameterIi(p0, p1));
    }

    public static void glGetSamplerParameterIu(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameterIu(p0, p1, buf)));
    }

    public static int glGetSamplerParameterIui(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameterIui(p0, p1));
    }

    public static float glGetSamplerParameterf(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameterf(p0, p1));
    }

    public static int glGetSamplerParameteri(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSamplerObjects.glGetSamplerParameteri(p0, p1));
    }

    public static boolean glIsSampler(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSamplerObjects.glIsSampler(p0));
    }

    public static void glSamplerParameter(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameter(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glSamplerParameter(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameter(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glSamplerParameterI(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameterI(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glSamplerParameterIu(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameterIu(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glSamplerParameterf(int p0, int p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameterf(p0, p1, p2));
    }

    public static void glSamplerParameteri(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSamplerObjects.glSamplerParameteri(p0, p1, p2));
    }
}
