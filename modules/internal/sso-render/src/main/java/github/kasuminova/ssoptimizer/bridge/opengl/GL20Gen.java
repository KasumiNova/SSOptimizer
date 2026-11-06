package github.kasuminova.ssoptimizer.bridge.opengl;

public class GL20Gen {
    protected GL20Gen() {
    }

    public static void glAttachShader(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glAttachShader(p0, p1));
    }

    public static void glBindAttribLocation(int p0, int p1, java.lang.CharSequence p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glBindAttribLocation(p0, p1, p2));
    }

    public static void glBindAttribLocation(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL20.glBindAttribLocation(p0, p1, snapshot));
    }

    public static void glBlendEquationSeparate(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glBlendEquationSeparate(p0, p1));
    }

    public static void glCompileShader(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glCompileShader(p0));
    }

    public static int glCreateProgram() {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glCreateProgram());
    }

    public static int glCreateShader(int p0) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glCreateShader(p0));
    }

    public static void glDeleteProgram(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDeleteProgram(p0));
    }

    public static void glDeleteShader(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDeleteShader(p0));
    }

    public static void glDetachShader(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDetachShader(p0, p1));
    }

    public static void glDisableVertexAttribArray(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDisableVertexAttribArray(p0));
    }

    public static void glDrawBuffers(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDrawBuffers(p0));
    }

    public static void glDrawBuffers(java.nio.IntBuffer p0) {
        BridgeSupport.enqueueSnapshot(p0, snapshot -> org.lwjgl.opengl.GL20.glDrawBuffers(snapshot.asIntBuffer()));
    }

    public static void glEnableVertexAttribArray(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glEnableVertexAttribArray(p0));
    }

    public static java.lang.String glGetActiveAttrib(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveAttrib(p0, p1, p2));
    }

    public static java.lang.String glGetActiveAttrib(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveAttrib(p0, p1, p2, p3));
    }

    public static void glGetActiveAttrib(int p0, int p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetActiveAttrib(p0, p1, p2, p3, p4, p5));
    }

    public static int glGetActiveAttribSize(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveAttribSize(p0, p1));
    }

    public static int glGetActiveAttribType(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveAttribType(p0, p1));
    }

    public static java.lang.String glGetActiveUniform(int p0, int p1, int p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveUniform(p0, p1, p2));
    }

    public static java.lang.String glGetActiveUniform(int p0, int p1, int p2, java.nio.IntBuffer p3) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveUniform(p0, p1, p2, p3));
    }

    public static void glGetActiveUniform(int p0, int p1, java.nio.IntBuffer p2, java.nio.IntBuffer p3, java.nio.IntBuffer p4, java.nio.ByteBuffer p5) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetActiveUniform(p0, p1, p2, p3, p4, p5));
    }

    public static int glGetActiveUniformSize(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveUniformSize(p0, p1));
    }

    public static int glGetActiveUniformType(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetActiveUniformType(p0, p1));
    }

    public static void glGetAttachedShaders(int p0, java.nio.IntBuffer p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetAttachedShaders(p0, p1, p2));
    }

    public static int glGetAttribLocation(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetAttribLocation(p0, p1));
    }

    public static int glGetAttribLocation(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetAttribLocation(p0, p1));
    }

    public static int glGetProgram(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetProgram(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetProgram(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetProgram(p0, p1, buf)));
    }

    public static java.lang.String glGetProgramInfoLog(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetProgramInfoLog(p0, p1));
    }

    public static void glGetProgramInfoLog(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetProgramInfoLog(p0, p1, p2));
    }

    public static int glGetProgrami(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetProgrami(p0, p1));
    }

    public static int glGetShader(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShader(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetShader(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetShader(p0, p1, buf)));
    }

    public static java.lang.String glGetShaderInfoLog(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShaderInfoLog(p0, p1));
    }

    public static void glGetShaderInfoLog(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetShaderInfoLog(p0, p1, p2));
    }

    public static java.lang.String glGetShaderSource(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShaderSource(p0, p1));
    }

    public static void glGetShaderSource(int p0, java.nio.IntBuffer p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> org.lwjgl.opengl.GL20.glGetShaderSource(p0, p1, p2));
    }

    public static int glGetShaderi(int p0, int p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShaderi(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetUniform(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetUniform(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetUniform(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetUniform(p0, p1, buf)));
    }

    public static int glGetUniformLocation(int p0, java.lang.CharSequence p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetUniformLocation(p0, p1));
    }

    public static int glGetUniformLocation(int p0, java.nio.ByteBuffer p1) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetUniformLocation(p0, p1));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetVertexAttrib(int p0, int p1, java.nio.DoubleBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillDoubles(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetVertexAttrib(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetVertexAttrib(int p0, int p1, java.nio.FloatBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillFloats(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetVertexAttrib(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetVertexAttrib(int p0, int p1, java.nio.IntBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillInts(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetVertexAttrib(p0, p1, buf)));
    }

    // 缓冲填充 getter（绕过 LWJGL2 固定 remaining>=16 检查）
    public static void glGetVertexAttribPointer(int p0, int p1, java.nio.ByteBuffer p2) {
        BridgeSupport.blockingWait(() -> GetBufferFill.fillBooleans(p2, 16, buf -> org.lwjgl.opengl.GL20.glGetVertexAttribPointer(p0, p1, buf)));
    }

    public static java.nio.ByteBuffer glGetVertexAttribPointer(int p0, int p1, long p2) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetVertexAttribPointer(p0, p1, p2));
    }

    public static boolean glIsProgram(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glIsProgram(p0));
    }

    public static boolean glIsShader(int p0) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glIsShader(p0));
    }

    public static void glLinkProgram(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glLinkProgram(p0));
    }

    public static void glShaderSource(int p0, java.lang.CharSequence p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glShaderSource(p0, p1));
    }

    public static void glShaderSource(int p0, java.lang.CharSequence[] p1) {
        final java.lang.CharSequence[] copy = p1.clone();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glShaderSource(p0, copy));
    }

    public static void glShaderSource(int p0, java.nio.ByteBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glShaderSource(p0, snapshot));
    }

    public static void glStencilFuncSeparate(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glStencilFuncSeparate(p0, p1, p2, p3));
    }

    public static void glStencilMaskSeparate(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glStencilMaskSeparate(p0, p1));
    }

    public static void glStencilOpSeparate(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glStencilOpSeparate(p0, p1, p2, p3));
    }

    public static void glUniform1(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform1(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform1(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform1(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform1f(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform1f(p0, p1));
    }

    public static void glUniform1i(int p0, int p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform1i(p0, p1));
    }

    public static void glUniform2(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform2(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform2(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform2(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform2f(int p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform2f(p0, p1, p2));
    }

    public static void glUniform2i(int p0, int p1, int p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform2i(p0, p1, p2));
    }

    public static void glUniform3(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform3(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform3(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform3(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform3f(int p0, float p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform3f(p0, p1, p2, p3));
    }

    public static void glUniform3i(int p0, int p1, int p2, int p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform3i(p0, p1, p2, p3));
    }

    public static void glUniform4(int p0, java.nio.FloatBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform4(p0, snapshot.asFloatBuffer()));
    }

    public static void glUniform4(int p0, java.nio.IntBuffer p1) {
        BridgeSupport.enqueueSnapshot(p1, snapshot -> org.lwjgl.opengl.GL20.glUniform4(p0, snapshot.asIntBuffer()));
    }

    public static void glUniform4f(int p0, float p1, float p2, float p3, float p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform4f(p0, p1, p2, p3, p4));
    }

    public static void glUniform4i(int p0, int p1, int p2, int p3, int p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform4i(p0, p1, p2, p3, p4));
    }

    public static void glUniformMatrix2(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL20.glUniformMatrix2(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix3(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL20.glUniformMatrix3(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix4(int p0, boolean p1, java.nio.FloatBuffer p2) {
        BridgeSupport.enqueueSnapshot(p2, snapshot -> org.lwjgl.opengl.GL20.glUniformMatrix4(p0, p1, snapshot.asFloatBuffer()));
    }

    public static void glUseProgram(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUseProgram(p0));
    }

    public static void glValidateProgram(int p0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glValidateProgram(p0));
    }

    public static void glVertexAttrib1d(int p0, double p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib1d(p0, p1));
    }

    public static void glVertexAttrib1f(int p0, float p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib1f(p0, p1));
    }

    public static void glVertexAttrib1s(int p0, short p1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib1s(p0, p1));
    }

    public static void glVertexAttrib2d(int p0, double p1, double p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib2d(p0, p1, p2));
    }

    public static void glVertexAttrib2f(int p0, float p1, float p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib2f(p0, p1, p2));
    }

    public static void glVertexAttrib2s(int p0, short p1, short p2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib2s(p0, p1, p2));
    }

    public static void glVertexAttrib3d(int p0, double p1, double p2, double p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib3d(p0, p1, p2, p3));
    }

    public static void glVertexAttrib3f(int p0, float p1, float p2, float p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib3f(p0, p1, p2, p3));
    }

    public static void glVertexAttrib3s(int p0, short p1, short p2, short p3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib3s(p0, p1, p2, p3));
    }

    public static void glVertexAttrib4Nub(int p0, byte p1, byte p2, byte p3, byte p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib4Nub(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4d(int p0, double p1, double p2, double p3, double p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib4d(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4f(int p0, float p1, float p2, float p3, float p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib4f(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttrib4s(int p0, short p1, short p2, short p3, short p4) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttrib4s(p0, p1, p2, p3, p4));
    }

    public static void glVertexAttribPointer(int p0, int p1, boolean p2, int p3, java.nio.DoubleBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, snapshot.asDoubleBuffer()));
    }

    public static void glVertexAttribPointer(int p0, int p1, boolean p2, int p3, java.nio.FloatBuffer p4) {
        BridgeSupport.enqueueSnapshot(p4, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, snapshot.asFloatBuffer()));
    }

    public static void glVertexAttribPointer(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glVertexAttribPointer(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.IntBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, p4, snapshot.asIntBuffer()));
    }

    public static void glVertexAttribPointer(int p0, int p1, boolean p2, boolean p3, int p4, java.nio.ShortBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, p4, snapshot.asShortBuffer()));
    }

    public static void glVertexAttribPointer(int p0, int p1, int p2, boolean p3, int p4, java.nio.ByteBuffer p5) {
        BridgeSupport.enqueueSnapshot(p5, snapshot -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, p4, snapshot));
    }

    public static void glVertexAttribPointer(int p0, int p1, int p2, boolean p3, int p4, long p5) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glVertexAttribPointer(p0, p1, p2, p3, p4, p5));
    }
}
