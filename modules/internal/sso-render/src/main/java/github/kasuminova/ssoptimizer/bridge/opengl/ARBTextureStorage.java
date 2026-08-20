package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.ARBTextureStorage 的 bridge 镜像（不可变纹理存储扩展族）。
 * <p>
 * 与 {@link GL42} 的 glTexStorage 族同语义（扩展版入口），BoxUtil 按驱动能力
 * 选择扩展或核心入口。语义同 {@link GL11}。
 */
public final class ARBTextureStorage {
    private ARBTextureStorage() {
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

    public static void glTexStorage2D(int target, int levels, int internalFormat, int width, int height) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBTextureStorage
                .glTexStorage2D(target, levels, internalFormat, width, height));
    }
}
