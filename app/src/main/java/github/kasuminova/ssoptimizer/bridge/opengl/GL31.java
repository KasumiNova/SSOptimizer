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
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glDrawArraysInstanced(mode, first, count, primcount));
    }

    public static void glTexBuffer(int target, int internalFormat, int buffer) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glTexBuffer(target, internalFormat, buffer));
    }

    public static void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL31.glUniformBlockBinding(
                program, uniformBlockIndex, uniformBlockBinding));
    }

    /** 名称查询：阻塞通道取回（调用方立即消费返回值）。名称在录制时刻定稿。 */
    public static int glGetUniformBlockIndex(int program, CharSequence uniformBlockName) {
        String name = uniformBlockName.toString();
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL31.glGetUniformBlockIndex(program, name));
    }
}
