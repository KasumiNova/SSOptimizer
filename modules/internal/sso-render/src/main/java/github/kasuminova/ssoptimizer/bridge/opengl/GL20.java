package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public final class GL20 extends GL20Gen {
    private GL20() {
    }

    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    static void uninstall() {
        BridgeSupport.uninstall();
    }

    public static int glCreateShader(int type) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glCreateShader(type));
    }

    public static void glShaderSource(int shader, CharSequence source) {
        String snapshot = source.toString();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glShaderSource(shader, snapshot));
    }

    public static void glShaderSource(int shader, CharSequence[] sources) {
        String[] snapshot = new String[sources.length];
        for (int i = 0; i < sources.length; i++) {
            snapshot[i] = sources[i].toString();
        }
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glShaderSource(shader, snapshot));
    }

    public static void glCompileShader(int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glCompileShader(shader));
    }

    public static void glAttachShader(int program, int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glAttachShader(program, shader));
    }

    public static void glValidateProgram(int program) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glValidateProgram(program));
    }

    public static void glUseProgram(int program) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace("USE_PROG", program, 0, 0, null);
        BridgeSupport.simulatedState().onUseProgram(program);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUseProgram(program));
    }

    public static void glUniform1i(int location, int v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform1i(location, v0));
    }

    public static void glUniform1f(int location, float v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform1f(location, v0));
    }

    public static void glUniform2f(int location, float v0, float v1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform2f(location, v0, v1));
    }

    public static void glUniform3f(int location, float v0, float v1, float v2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform3f(location, v0, v1, v2));
    }

    public static void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform4f(location, v0, v1, v2, v3));
    }

    public static int glGetShaderi(int shader, int pname) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glGetShaderi(shader, pname));
    }

    public static int glGetProgrami(int program, int pname) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glGetProgrami(program, pname));
    }

    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader, maxLength));
    }

    public static String glGetProgramInfoLog(int program, int maxLength) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glGetProgramInfoLog(program, maxLength));
    }

    public static void glDeleteShader(int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDeleteShader(shader));
    }

    public static int glCreateProgram() {
        final int program = GL20Gen.glCreateProgram();
        BridgeSupport.simulatedState().onCreateProgram(program);
        return program;
    }

    public static void glDeleteProgram(int program) {
        BridgeSupport.simulatedState().onDeleteProgram(program);
        GL20Gen.glDeleteProgram(program);
    }

    public static void glDetachShader(int program, int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDetachShader(program, shader));
    }

    public static void glEnableVertexAttribArray(int index) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glEnableVertexAttribArray(index));
    }

    public static void glDisableVertexAttribArray(int index) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDisableVertexAttribArray(index));
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, long offset) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL20.glVertexAttribPointer(index, size, type, normalized, stride, offset));
    }

    public static void glUniform2i(int location, int v0, int v1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform2i(location, v0, v1));
    }

    public static void glUniform3i(int location, int v0, int v1, int v2) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform3i(location, v0, v1, v2));
    }

    public static void glUniform4i(int location, int v0, int v1, int v2, int v3) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glUniform4i(location, v0, v1, v2, v3));
    }

    public static void glUniform3(int location, FloatBuffer values) {
        BridgeSupport.enqueueSnapshot(values, snapshot ->
                org.lwjgl.opengl.GL20.glUniform3(location, snapshot.asFloatBuffer()));
    }

    public static void glUniform4(int location, FloatBuffer values) {
        BridgeSupport.enqueueSnapshot(values, snapshot ->
                org.lwjgl.opengl.GL20.glUniform4(location, snapshot.asFloatBuffer()));
    }

    public static void glUniformMatrix4(int location, boolean transpose, FloatBuffer matrices) {
        BridgeSupport.enqueueSnapshot(matrices, snapshot ->
                org.lwjgl.opengl.GL20.glUniformMatrix4(location, transpose, snapshot.asFloatBuffer()));
    }

    public static void glDrawBuffers(int buffer) {
        BridgeSupport.simulatedState().onDrawBuffer(buffer);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDrawBuffers(buffer));
    }

    public static void glDrawBuffers(IntBuffer buffers) {
        // GL_DRAW_BUFFER 单值查询语义即 DRAW_BUFFER0：簿记首元素（绝对读取不动 position）
        if (buffers.remaining() >= 1) {
            BridgeSupport.simulatedState().onDrawBuffers(buffers.get(buffers.position()));
        }
        BridgeSupport.enqueueSnapshot(buffers, snapshot ->
                org.lwjgl.opengl.GL20.glDrawBuffers(snapshot.asIntBuffer()));
    }

    public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        BridgeSupport.blockingWaitResource(() ->
                org.lwjgl.opengl.GL20.glGetAttachedShaders(program, count, shaders));
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
        String nameStr = name.toString();
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glBindAttribLocation(program, index, nameStr));
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        String nameStr = name.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL20.glGetAttribLocation(program, nameStr));
    }

    /** 主线程读取录制侧名字簿记，aux 线程阻塞查询真实状态。 */
    public static boolean glIsProgram(int program) {
        if (BridgeSupport.isMainRecordingThread()) {
            BridgeSupport.resyncSimulatedStateIfAuxDirty();
            return BridgeSupport.simulatedState().isProgram(program);
        }
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glIsProgram(program));
    }

    public static void glUniformMatrix3(int location, boolean transpose, FloatBuffer matrices) {
        BridgeSupport.enqueueSnapshot(matrices, snapshot ->
                org.lwjgl.opengl.GL20.glUniformMatrix3(location, transpose, snapshot.asFloatBuffer()));
    }
}
