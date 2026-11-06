package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBShaderObjects {
    private ARBShaderObjects() {
    }

    public static void glAttachObjectARB(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glAttachObjectARB(p0, p1));
    }

    public static void glCompileShaderARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glCompileShaderARB(p0));
    }

    public static int glCreateProgramObjectARB() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBShaderObjects.glCreateProgramObjectARB());
    }

    public static int glCreateShaderObjectARB(int p0) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBShaderObjects.glCreateShaderObjectARB(p0));
    }

    public static void glDeleteObjectARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glDeleteObjectARB(p0));
    }

    public static void glDetachObjectARB(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glDetachObjectARB(p0, p1));
    }

    public static java.lang.String glGetActiveUniformARB(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetActiveUniformARB(p0, p1, p2));
    }

    public static java.lang.String glGetActiveUniformARB(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetActiveUniformARB(p0, p1, p2, p3));
    }

    public static void glGetActiveUniformARB(int p0, int p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBShaderObjects.glGetActiveUniformARB(p0, p1, p2, p3, p4, p5));
    }

    public static int glGetActiveUniformSizeARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetActiveUniformSizeARB(p0, p1));
    }

    public static int glGetActiveUniformTypeARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetActiveUniformTypeARB(p0, p1));
    }

    public static void glGetAttachedObjectsARB(int p0, java.nio.IntBuffer p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBShaderObjects.glGetAttachedObjectsARB(p0, p1, p2));
    }

    public static int glGetHandleARB(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetHandleARB(p0));
    }

    public static java.lang.String glGetInfoLogARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetInfoLogARB(p0, p1));
    }

    public static void glGetInfoLogARB(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBShaderObjects.glGetInfoLogARB(p0, p1, p2));
    }

    public static void glGetObjectParameterARB(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameterARB(p0, p1, buf)));
    }

    public static void glGetObjectParameterARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameterARB(p0, p1, buf)));
    }

    public static float glGetObjectParameterfARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameterfARB(p0, p1));
    }

    public static int glGetObjectParameteriARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetObjectParameteriARB(p0, p1));
    }

    public static java.lang.String glGetShaderSourceARB(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetShaderSourceARB(p0, p1));
    }

    public static void glGetShaderSourceARB(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBShaderObjects.glGetShaderSourceARB(p0, p1, p2));
    }

    public static void glGetUniformARB(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformARB(p0, p1, buf)));
    }

    public static void glGetUniformARB(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformARB(p0, p1, buf)));
    }

    public static int glGetUniformLocationARB(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformLocationARB(p0, p1));
    }

    public static int glGetUniformLocationARB(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBShaderObjects.glGetUniformLocationARB(p0, p1));
    }

    public static void glLinkProgramARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glLinkProgramARB(p0));
    }

    public static void glShaderSourceARB(int p0, java.lang.CharSequence p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glShaderSourceARB(p0, p1));
    }

    public static void glShaderSourceARB(int p0, java.lang.CharSequence[] p1) {
        final java.lang.CharSequence[] copy = p1.clone();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glShaderSourceARB(p0, copy));
    }

    public static void glShaderSourceARB(int p0, java.nio.ByteBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glShaderSourceARB(p0, snapshot));
    }

    public static void glUniform1ARB(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform1ARB(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform1ARB(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform1ARB(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform1fARB(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform1fARB(p0, p1));
    }

    public static void glUniform1iARB(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform1iARB(p0, p1));
    }

    public static void glUniform2ARB(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform2ARB(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform2ARB(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform2ARB(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform2fARB(int p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform2fARB(p0, p1, p2));
    }

    public static void glUniform2iARB(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform2iARB(p0, p1, p2));
    }

    public static void glUniform3ARB(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform3ARB(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform3ARB(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform3ARB(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform3fARB(int p0, float p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform3fARB(p0, p1, p2, p3));
    }

    public static void glUniform3iARB(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform3iARB(p0, p1, p2, p3));
    }

    public static void glUniform4ARB(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform4ARB(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform4ARB(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniform4ARB(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform4fARB(int p0, float p1, float p2, float p3, float p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform4fARB(p0, p1, p2, p3, p4));
    }

    public static void glUniform4iARB(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUniform4iARB(p0, p1, p2, p3, p4));
    }

    public static void glUniformMatrix2ARB(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniformMatrix2ARB(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix3ARB(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniformMatrix3ARB(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix4ARB(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBShaderObjects.glUniformMatrix4ARB(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUseProgramObjectARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glUseProgramObjectARB(p0));
    }

    public static void glValidateProgramARB(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBShaderObjects.glValidateProgramARB(p0));
    }
}
