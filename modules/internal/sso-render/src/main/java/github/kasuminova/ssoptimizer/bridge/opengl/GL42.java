package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL42 的 bridge 镜像（不可变纹理存储/图像绑定/内存屏障族）。
 * <p>
 * 动机：BoxUtil 的纹理分配（glTexStorage*）与 image store 路径走 GL42 入口。
 * 语义同 {@link GL11}：全部为状态/分配命令，按提交序入队。
 */
public final class GL42 {
    private GL42() {
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

    public static void glBindImageTexture(int unit, int texture, int level, boolean layered,
                                          int layer, int access, int format) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glBindImageTexture(
                unit, texture, level, layered, layer, access, format));
    }

    public static void glMemoryBarrier(int barriers) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glMemoryBarrier(barriers));
    }

    public static void glTexStorage1D(int target, int levels, int internalFormat, int width) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage1D(target, levels, internalFormat, width));
    }

    public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage2D(target, levels, internalFormat, width, height));
    }

    public static void glTexStorage3D(int target, int levels, int internalFormat,
                                      int width, int height, int depth) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glTexStorage3D(
                target, levels, internalFormat, width, height, depth));
    }

    // ------------------------------------------------------------------
    // 盘点补面：base-instance 实例化绘制族（BoxUtil 1.0.6 GLWrapper$Drawcall
    // 引用；录制语义同 {@link ARBDrawInstanced}：索引 buffer 快照入队，VBO 偏移传值）
    // ------------------------------------------------------------------

    public static void glDrawArraysInstancedBaseInstance(int mode, int first, int count,
                                                         int primcount, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawArraysInstancedBaseInstance(
                mode, first, count, primcount, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.ByteBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot, primcount, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.IntBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot.asIntBuffer(),
                        primcount, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseInstance(int mode, java.nio.ShortBuffer indices,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(mode, snapshot.asShortBuffer(),
                        primcount, baseInstance));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsInstancedBaseInstance(int mode, int count, int type, long indicesOffset,
                                                           int primcount, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseInstance(
                mode, count, type, indicesOffset, primcount, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.ByteBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot,
                        primcount, baseVertex, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.IntBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot.asIntBuffer(),
                        primcount, baseVertex, baseInstance));
    }

    /** 索引 buffer 录制时刻快照。 */
    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, java.nio.ShortBuffer indices,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(mode, snapshot.asShortBuffer(),
                        primcount, baseVertex, baseInstance));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glDrawElementsInstancedBaseVertexBaseInstance(int mode, int count, int type, long indicesOffset,
                                                                     int primcount, int baseVertex, int baseInstance) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL42.glDrawElementsInstancedBaseVertexBaseInstance(
                mode, count, type, indicesOffset, primcount, baseVertex, baseInstance));
    }
}
