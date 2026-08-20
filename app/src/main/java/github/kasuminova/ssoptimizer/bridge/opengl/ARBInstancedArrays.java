package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.ARBInstancedArrays 的 bridge 镜像（实例化属性除数 ARB 变体）。
 * <p>
 * 动机：GL33.glVertexAttribDivisor 的 ARB 扩展别名（LWJGL2 中同功能），模组按
 * 能力探测二选一（Particle Engine 即如此），两个入口都必须覆盖。
 */
public final class ARBInstancedArrays {
    private ARBInstancedArrays() {
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

    /** 语义同 {@link GL33#glVertexAttribDivisor}。 */
    public static void glVertexAttribDivisorARB(int index, int divisor) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.ARBInstancedArrays.glVertexAttribDivisorARB(index, divisor));
    }
}
