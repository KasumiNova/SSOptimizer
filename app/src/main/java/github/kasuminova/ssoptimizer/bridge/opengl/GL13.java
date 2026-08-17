package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL13 的 bridge 镜像。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体未用 GL13，GraphicsLib 的多纹理单元
 * （glActiveTexture，shader 前置）必须覆盖，其余 GL13 面（压缩纹理等）本阶段不做。
 */
public final class GL13 {
    private GL13() {
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

    public static void glActiveTexture(int texture) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL13.glActiveTexture(texture));
    }
}
