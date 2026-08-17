package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL14 的 bridge 镜像。
 * <p>
 * 动机同 {@link GL11}。盘点结论：游戏本体使用 glBlendEquation（×18），
 * 必须覆盖；GL14 其余面（glBlendFuncSeparate/glPointParameter 等）本阶段不做。
 */
public final class GL14 {
    private GL14() {
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

    public static void glBlendEquation(int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendEquation(mode));
    }
}
