package github.kasuminova.ssoptimizer.bridge.opengl;
public final class ARBSeparateShaderObjects {
    private ARBSeparateShaderObjects() {
    }

    public static void glActiveShaderProgram(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glActiveShaderProgram(p0, p1));
    }

    public static void glBindProgramPipeline(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glBindProgramPipeline(p0));
    }

    public static int glCreateShaderProgram(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glCreateShaderProgram(p0, p1));
    }

    public static int glCreateShaderProgram(int p0, java.lang.CharSequence[] p1) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glCreateShaderProgram(p0, p1));
    }

    public static int glCreateShaderProgram(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glCreateShaderProgram(p0, p1));
    }

    public static int glCreateShaderProgram(int p0, java.nio.ByteBuffer[] p1) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glCreateShaderProgram(p0, p1));
    }

    public static int glCreateShaderProgram(int p0, int p1, java.nio.ByteBuffer p2) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glCreateShaderProgram(p0, p1, p2));
    }

    public static void glDeleteProgramPipelines(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glDeleteProgramPipelines(p0));
    }

    public static void glDeleteProgramPipelines(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glDeleteProgramPipelines(snapshot.asIntBuffer()));
    }

    public static int glGenProgramPipelines() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGenProgramPipelines());
    }

    public static void glGenProgramPipelines(java.nio.IntBuffer p0) {
        BridgeSupport.blockingWaitResource(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGenProgramPipelines(p0));
    }

    public static void glGetProgramPipeline(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGetProgramPipeline(p0, p1, buf)));
    }

    public static java.lang.String glGetProgramPipelineInfoLog(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGetProgramPipelineInfoLog(p0, p1));
    }

    public static void glGetProgramPipelineInfoLog(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGetProgramPipelineInfoLog(p0, p1, p2));
    }

    public static int glGetProgramPipelinei(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glGetProgramPipelinei(p0, p1));
    }

    public static boolean glIsProgramPipeline(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glIsProgramPipeline(p0));
    }

    public static void glProgramParameteri(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramParameteri(p0, p1, p2));
    }

    public static void glProgramUniform1(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniform1(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniform1(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform1d(int p0, int p1, double p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1d(p0, p1, p2));
    }

    public static void glProgramUniform1f(int p0, int p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1f(p0, p1, p2));
    }

    public static void glProgramUniform1i(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1i(p0, p1, p2));
    }

    public static void glProgramUniform1u(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1u(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform1ui(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform1ui(p0, p1, p2));
    }

    public static void glProgramUniform2(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniform2(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniform2(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform2d(int p0, int p1, double p2, double p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2d(p0, p1, p2, p3));
    }

    public static void glProgramUniform2f(int p0, int p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2f(p0, p1, p2, p3));
    }

    public static void glProgramUniform2i(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2i(p0, p1, p2, p3));
    }

    public static void glProgramUniform2u(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2u(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform2ui(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform2ui(p0, p1, p2, p3));
    }

    public static void glProgramUniform3(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniform3(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniform3(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform3d(int p0, int p1, double p2, double p3, double p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3d(p0, p1, p2, p3, p4));
    }

    public static void glProgramUniform3f(int p0, int p1, float p2, float p3, float p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3f(p0, p1, p2, p3, p4));
    }

    public static void glProgramUniform3i(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3i(p0, p1, p2, p3, p4));
    }

    public static void glProgramUniform3u(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3u(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform3ui(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform3ui(p0, p1, p2, p3, p4));
    }

    public static void glProgramUniform4(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4(p0, p1, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniform4(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniform4(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform4d(int p0, int p1, double p2, double p3, double p4, double p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4d(p0, p1, p2, p3, p4, p5));
    }

    public static void glProgramUniform4f(int p0, int p1, float p2, float p3, float p4, float p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4f(p0, p1, p2, p3, p4, p5));
    }

    public static void glProgramUniform4i(int p0, int p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4i(p0, p1, p2, p3, p4, p5));
    }

    public static void glProgramUniform4u(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4u(p0, p1, snapshot.asIntBuffer()));
    }

    public static void glProgramUniform4ui(int p0, int p1, int p2, int p3, int p4, int p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniform4ui(p0, p1, p2, p3, p4, p5));
    }

    public static void glProgramUniformMatrix2(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix2(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix2x3(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2x3(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix2x3(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2x3(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix2x4(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2x4(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix2x4(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix2x4(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix3(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix3(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix3x2(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3x2(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix3x2(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3x2(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix3x4(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3x4(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix3x4(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix3x4(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix4(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix4(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix4x2(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4x2(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix4x2(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4x2(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glProgramUniformMatrix4x3(int p0, int p1, boolean p2, java.nio.DoubleBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4x3(p0, p1, p2, snapshot.asDoubleBuffer()));
    }

    public static void glProgramUniformMatrix4x3(int p0, int p1, boolean p2, java.nio.FloatBuffer p3) {
        BridgeSupport.enqueueSnapshot(p3, snapshot -> org.lwjgl.opengl.ARBSeparateShaderObjects.glProgramUniformMatrix4x3(p0, p1, p2, snapshot.asFloatBuffer()));
    }

    public static void glUseProgramStages(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glUseProgramStages(p0, p1, p2));
    }

    public static void glValidateProgramPipeline(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBSeparateShaderObjects.glValidateProgramPipeline(p0));
    }
}
