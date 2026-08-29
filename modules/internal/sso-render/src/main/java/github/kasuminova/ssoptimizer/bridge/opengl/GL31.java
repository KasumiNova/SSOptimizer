package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL31 的 bridge 镜像（实例化绘制/UBO/缓冲拷贝族）。
 * <p>
 * 动机：BoxUtil 的实例化渲染与 UBO 绑定走 GL31 入口。语义同 {@link GL11}：
 * 状态命令按提交序入队，索引查询走阻塞通道。
 */
public final class GL31 {
    private GL31() {
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

    public static void glCopyBufferSubData(int readTarget, int writeTarget,
                                           long readOffset, long writeOffset, long size) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glCopyBufferSubData(
                readTarget, writeTarget, readOffset, writeOffset, size));
    }

    public static void glDrawArraysInstanced(int mode, int first, int count, int primcount) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "DRAW_INST", mode, count, primcount, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glDrawArraysInstanced(mode, first, count, primcount));
    }

    public static void glTexBuffer(int target, int internalFormat, int buffer) {
        github.kasuminova.ssoptimizer.common.render.queue.RtTrace.trace(
                "TEXBUF", target, internalFormat, buffer, null);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glTexBuffer(target, internalFormat, buffer));
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glUniformBlockBinding(
                program, uniformBlockIndex, uniformBlockBinding));
    }

    /** 名称查询：阻塞通道取回（调用方立即消费返回值）。名称在录制时刻定稿。 */
    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        String name = uniformBlockName.toString();
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name));
    }

    // ------------------------------------------------------------------
    // glDrawElementsInstanced 族（BoxUtil 1.0.6 GLWrapper$Drawcall 引用；
    // 录制语义同 {@link ARBDrawInstanced}：索引 buffer 快照入队，VBO 偏移传值）
    // ------------------------------------------------------------------

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstanced(int mode, java.nio.ByteBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot, primcount));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstanced(int mode, java.nio.IntBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot.asIntBuffer(), primcount));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstanced(int mode, java.nio.ShortBuffer indices, int primcount) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, snapshot.asShortBuffer(), primcount));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsInstanced(int mode, int count, int type, long indicesOffset, int primcount) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL31.glDrawElementsInstanced(mode, count, type, indicesOffset, primcount));
    }

    // ------------------------------------------------------------------
    // 盘点补面：uniform block 查询族（BoxUtil shader 反射引用；阻塞通道取回）
    // ------------------------------------------------------------------

    /** 名称查询（ByteBuffer 形态）：阻塞通道取回。名称字节在录制时刻读出定稿。 */
    public static int glGetUniformBlockIndex(int program, java.nio.ByteBuffer uniformBlockName) {
        String name = readNullTerminatedName(uniformBlockName);
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name));
    }

    /** 属性查询：阻塞通道取回。 */
    public static int glGetActiveUniformBlocki(int program, int uniformBlockIndex, int pname) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformBlocki(program, uniformBlockIndex, pname));
    }

    /** 属性查询：阻塞通道取回。 */
    public static int glGetActiveUniformsi(int program, int uniformIndex, int pname) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformsi(program, uniformIndex, pname));
    }

    /** 名称查询：阻塞通道取回。 */
    public static String glGetActiveUniformBlockName(int program, int uniformBlockIndex, int maxLength) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformBlockName(program, uniformBlockIndex, maxLength));
    }

    /** 名称查询：阻塞通道取回。 */
    public static String glGetActiveUniformName(int program, int uniformIndex, int maxLength) {
        return BridgeSupport.blockingGet(() ->
                org.lwjgl.opengl.GL31.glGetActiveUniformName(program, uniformIndex, maxLength));
    }

    /** LWJGL2 的 ByteBuffer 名称参数是 NUL 结尾 ASCII；录制时刻读出（不改 position）。 */
    private static String readNullTerminatedName(java.nio.ByteBuffer name) {
        java.nio.ByteBuffer view = name.duplicate();
        StringBuilder sb = new StringBuilder(view.remaining());
        while (view.hasRemaining()) {
            byte b = view.get();
            if (b == 0) {
                break;
            }
            sb.append((char) (b & 0xFF));
        }
        return sb.toString();
    }
}
