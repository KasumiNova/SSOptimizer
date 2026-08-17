package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * org.lwjgl.opengl.GL20 的 bridge 镜像（GraphicsLib 需要的最小集）。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体零 shader，GL20 面仅为 GraphicsLib
 * 覆盖。本阶段语义：
 * <ul>
 *   <li>shader 源码参数（glShaderSource/glGetUniformLocation 的名称）在录制时刻
 *       固化为不可变 {@link String}（CharSequence 可能是可变的 StringBuilder，
 *       与 buffer 快照同一防护思路）；</li>
 *   <li>创建/编译/链接期的回读（glCreateShader/glCreateProgram/
 *       glGetUniformLocation/glGetShaderi/glGetProgrami/InfoLog）走阻塞通道——
 *       这些是加载期一次性调用，drain 可接受；运行期命令（glUseProgram/
 *       glUniform*）按普通命令录制；</li>
 *   <li>GL20 其余面（顶点属性/矩阵 uniform/多重 draw buffer 等）本阶段不做。</li>
 * </ul>
 */
public final class GL20 {
    private GL20() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /** 资源分配：阻塞通道取回真实 shader id。 */
    public static int glCreateShader(int type) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glCreateShader(type));
    }

    /** 源码录制时刻固化为 String，防止调用方随后改写 CharSequence。 */
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

    public static void glLinkProgram(int program) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glLinkProgram(program));
    }

    public static void glValidateProgram(int program) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glValidateProgram(program));
    }

    public static void glUseProgram(int program) {
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

    /** uniform 名录制时刻固化为 String；阻塞通道取回 location。 */
    public static int glGetUniformLocation(int program, CharSequence name) {
        String snapshot = name.toString();
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetUniformLocation(program, snapshot));
    }

    /** 编译期校验回读：阻塞通道取回。 */
    public static int glGetShaderi(int shader, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShaderi(shader, pname));
    }

    /** 链接期校验回读：阻塞通道取回。 */
    public static int glGetProgrami(int program, int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetProgrami(program, pname));
    }

    /** 编译日志回读：阻塞通道取回。 */
    public static String glGetShaderInfoLog(int shader, int maxLength) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetShaderInfoLog(shader, maxLength));
    }

    /** 链接日志回读：阻塞通道取回。 */
    public static String glGetProgramInfoLog(int program, int maxLength) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL20.glGetProgramInfoLog(program, maxLength));
    }

    public static void glDeleteShader(int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDeleteShader(shader));
    }

    /** 资源分配：阻塞通道取回真实 program id。 */
    public static int glCreateProgram() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.GL20::glCreateProgram);
    }

    public static void glDeleteProgram(int program) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDeleteProgram(program));
    }

    // ------------------------------------------------------------------
    // 盘点补面：模组（GraphicsLib/BoxUtil 等）实际使用的顶点属性与 uniform 变体
    // ------------------------------------------------------------------

    public static void glDetachShader(int program, int shader) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDetachShader(program, shader));
    }

    public static void glEnableVertexAttribArray(int index) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glEnableVertexAttribArray(index));
    }

    public static void glDisableVertexAttribArray(int index) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDisableVertexAttribArray(index));
    }

    /** VBO 偏移形态（offset 是绑定 VBO 内的字节偏移，跨线程语义不变）。 */
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

    /** 向量 uniform：buffer 快照后入队。 */
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

    /** 单缓冲 draw buffers 形态（GL11.glDrawBuffer 的多目标版）。 */
    public static void glDrawBuffers(int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL20.glDrawBuffers(buffer));
    }

    public static void glDrawBuffers(IntBuffer buffers) {
        BridgeSupport.enqueueSnapshot(buffers, snapshot ->
                org.lwjgl.opengl.GL20.glDrawBuffers(snapshot.asIntBuffer()));
    }

    /** 链接信息回读（编译期一次性）：阻塞通道。 */
    public static void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        BridgeSupport.blockingWait(() ->
                org.lwjgl.opengl.GL20.glGetAttachedShaders(program, count, shaders));
    }
}
