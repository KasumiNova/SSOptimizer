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
        BridgeSupport.simulatedState().onBlendEquation(mode);
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL14.glBlendEquation(mode));
    }

    /** 分离式混合因子（模组路径使用，盘点补面）。 */
    public static void glBlendFuncSeparate(int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        BridgeSupport.enqueue(() ->
                org.lwjgl.opengl.GL14.glBlendFuncSeparate(srcRGB, dstRGB, srcAlpha, dstAlpha));
    }

    /**
     * 批量 glDrawArrays（BoxUtil 1.0.6 GLWrapper$Drawcall 引用）。
     * 两个参数数组都在录制时刻快照；pointer 状态由命令流内此前的 pointer 命令
     * 按提交序重放（与 {@link ARBDrawInstanced} 的既有语义一致——本族非
     * immediate 热路径，不进池化 DrawCommand）。
     */
    public static void glMultiDrawArrays(int mode, java.nio.IntBuffer firsts, java.nio.IntBuffer counts) {
        java.nio.ByteBuffer firstsSnapshot = BridgeSupport.pool().snapshot(firsts);
        java.nio.ByteBuffer countsSnapshot = BridgeSupport.pool().snapshot(counts);
        BridgeSupport.enqueue(() -> {
            try {
                org.lwjgl.opengl.GL14.glMultiDrawArrays(mode, firstsSnapshot.asIntBuffer(),
                        countsSnapshot.asIntBuffer());
            } finally {
                BridgeSupport.releaseSnapshot(firstsSnapshot);
                BridgeSupport.releaseSnapshot(countsSnapshot);
            }
        });
    }
}
