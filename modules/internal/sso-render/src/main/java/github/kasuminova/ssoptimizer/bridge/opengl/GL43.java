package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.KHRDebugCallback;

import java.nio.IntBuffer;

/**
 * org.lwjgl.opengl.GL43 的 bridge 镜像（compute dispatch/调试输出/缓冲失效族）。
 * <p>
 * 动机：BoxUtil 的 compute shader 派发与 KHR_debug 注册走 GL43 入口。
 * 语义同 {@link GL11}；调试回调注册本身入队到渲染线程执行（回调随后在
 * 渲染线程上触发，调用方线程模型与折叠架构一致）。
 */
public final class GL43 {
    private GL43() {
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

    /** 回调注册入队：真实注册发生在渲染线程，回调也在渲染线程触发。 */
    public static void glDebugMessageCallback(KHRDebugCallback callback) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDebugMessageCallback(callback));
    }

    /** 过滤 id 列表录制时刻快照入队。 */
    public static void glDebugMessageControl(int source, int type, int severity,
                                             IntBuffer ids, boolean enabled) {
        BridgeSupport.enqueueSnapshot(ids, snapshot ->
                org.lwjgl.opengl.GL43.glDebugMessageControl(source, type, severity,
                        snapshot.asIntBuffer(), enabled));
    }

    public static void glDispatchCompute(int numGroupsX, int numGroupsY, int numGroupsZ) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glDispatchCompute(numGroupsX, numGroupsY, numGroupsZ));
    }

    public static void glInvalidateBufferData(int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferData(buffer));
    }

    // ------------------------------------------------------------------
    // 盘点补面：multi-indirect 绘制族（BoxUtil 1.0.6 GLWrapper$Drawcall 引用；
    // 参数 buffer 快照入队，VBO 偏移形态传值）
    // ------------------------------------------------------------------

    /** indirect 参数 buffer 录制时刻快照。 */
    public static void glMultiDrawArraysIndirect(int mode, java.nio.ByteBuffer indirect,
                                                 int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, snapshot, drawcount, stride));
    }

    /** indirect 参数 buffer 录制时刻快照。 */
    public static void glMultiDrawArraysIndirect(int mode, IntBuffer indirect, int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, snapshot.asIntBuffer(), drawcount, stride));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glMultiDrawArraysIndirect(int mode, long indirectOffset, int drawcount, int stride) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glMultiDrawArraysIndirect(mode, indirectOffset, drawcount, stride));
    }

    /** indirect 参数 buffer 录制时刻快照。 */
    public static void glMultiDrawElementsIndirect(int mode, int type, java.nio.ByteBuffer indirect,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, snapshot, drawcount, stride));
    }

    /** indirect 参数 buffer 录制时刻快照。 */
    public static void glMultiDrawElementsIndirect(int mode, int type, IntBuffer indirect,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueueSnapshot(indirect, snapshot ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, snapshot.asIntBuffer(),
                        drawcount, stride));
    }

    /** VBO 偏移形态：纯值参数，直接入队。 */
    public static void glMultiDrawElementsIndirect(int mode, int type, long indirectOffset,
                                                   int drawcount, int stride) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glMultiDrawElementsIndirect(mode, type, indirectOffset, drawcount, stride));
    }

    // ------------------------------------------------------------------
    // 盘点补面：SSBO/TBO/FBO/失效/多重采样存储状态族（BoxUtil 引用，
    // 纯值参数直接入队；buffer 参数录制时刻快照）
    // ------------------------------------------------------------------

    public static void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glShaderStorageBlockBinding(
                program, storageBlockIndex, storageBlockBinding));
    }

    public static void glTexBufferRange(int target, int internalFormat, int buffer, long offset, long size) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL43.glTexBufferRange(target, internalFormat, buffer, offset, size));
    }

    public static void glFramebufferParameteri(int target, int pname, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glFramebufferParameteri(target, pname, value));
    }

    public static void glInvalidateBufferSubData(int buffer, long offset, long length) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateBufferSubData(buffer, offset, length));
    }

    /** attachment 列表录制时刻快照入队。 */
    public static void glInvalidateFramebuffer(int target, IntBuffer attachments) {
        BridgeSupport.enqueueSnapshot(attachments, snapshot ->
                org.lwjgl.opengl.GL43.glInvalidateFramebuffer(target, snapshot.asIntBuffer()));
    }

    /** attachment 列表录制时刻快照入队。 */
    public static void glInvalidateSubFramebuffer(int target, IntBuffer attachments,
                                                  int x, int y, int width, int height) {
        BridgeSupport.enqueueSnapshot(attachments, snapshot ->
                org.lwjgl.opengl.GL43.glInvalidateSubFramebuffer(target, snapshot.asIntBuffer(),
                        x, y, width, height));
    }

    public static void glInvalidateTexImage(int texture, int level) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexImage(texture, level));
    }

    public static void glInvalidateTexSubImage(int texture, int level,
                                               int xOffset, int yOffset, int zOffset,
                                               int width, int height, int depth) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glInvalidateTexSubImage(
                texture, level, xOffset, yOffset, zOffset, width, height, depth));
    }

    public static void glTexStorage2DMultisample(int target, int samples, int internalFormat,
                                                 int width, int height, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage2DMultisample(
                target, samples, internalFormat, width, height, fixedSampleLocations));
    }

    public static void glTexStorage3DMultisample(int target, int samples, int internalFormat,
                                                 int width, int height, int depth, boolean fixedSampleLocations) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glTexStorage3DMultisample(
                target, samples, internalFormat, width, height, depth, fixedSampleLocations));
    }

    /** 顶点 binding 除数（BoxUtil 引用，盘点补面）：纯值参数，直接入队。 */
    public static void glVertexBindingDivisor(int bindingindex, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL43.glVertexBindingDivisor(bindingindex, divisor));
    }
}
