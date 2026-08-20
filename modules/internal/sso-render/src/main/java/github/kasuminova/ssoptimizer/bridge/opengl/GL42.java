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
}
