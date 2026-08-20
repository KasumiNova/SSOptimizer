package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

/**
 * org.lwjgl.opengl.GL41 的 bridge 镜像（program uniform/深度范围族）。
 * <p>
 * 动机：BoxUtil 的 program 级 uniform 设置走 GL41 入口。语义同 {@link GL11}。
 */
public final class GL41 {
    private GL41() {
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

    public static void glClearDepthf(float d) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glClearDepthf(d));
    }

    public static void glDepthRangef(float n, float f) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glDepthRangef(n, f));
    }

    public static void glProgramUniform1f(int program, int location, float v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform1f(program, location, v0));
    }

    public static void glProgramUniform1i(int program, int location, int v0) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform1i(program, location, v0));
    }

    public static void glProgramUniform2f(int program, int location, float v0, float v1) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL41.glProgramUniform2f(program, location, v0, v1));
    }
}
