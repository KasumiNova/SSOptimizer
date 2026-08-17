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
}
