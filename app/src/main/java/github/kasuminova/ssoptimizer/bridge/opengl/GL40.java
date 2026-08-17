package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;

import java.nio.IntBuffer;

/**
 * org.lwjgl.opengl.GL40 的 bridge 镜像（分绘制缓冲混合/细分/subroutine 族）。
 * <p>
 * 动机：BoxUtil 的多 draw buffer 独立混合与 shader subroutine 查询走 GL40 入口。
 * 语义同 {@link GL11}。
 */
public final class GL40 {
    private GL40() {
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

    public static void glBlendEquationi(int buf, int mode) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendEquationi(buf, mode));
    }

    public static void glBlendFunci(int buf, int src, int dst) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendFunci(buf, src, dst));
    }

    public static void glBlendFuncSeparatei(int buf, int srcRGB, int dstRGB, int srcAlpha, int dstAlpha) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glBlendFuncSeparatei(buf, srcRGB, dstRGB, srcAlpha, dstAlpha));
    }

    public static void glPatchParameteri(int pname, int value) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.GL40.glPatchParameteri(pname, value));
    }

    /** 索引数据录制时刻快照入队。 */
    public static void glUniformSubroutinesu(int shadertype, IntBuffer indices) {
        BridgeSupport.enqueueSnapshot(indices, snapshot ->
                org.lwjgl.opengl.GL40.glUniformSubroutinesu(shadertype, snapshot.asIntBuffer()));
    }

    /** 名称查询：阻塞通道取回。名称在录制时刻定稿。 */
    public static int glGetSubroutineIndex(int program, int shadertype, CharSequence name) {
        String nameStr = name.toString();
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL40.glGetSubroutineIndex(program, shadertype, nameStr));
    }

    /** 名称查询：阻塞通道取回。名称在录制时刻定稿。 */
    public static int glGetSubroutineUniformLocation(int program, int shadertype, CharSequence name) {
        String nameStr = name.toString();
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL40.glGetSubroutineUniformLocation(program, shadertype, nameStr));
    }
}
