package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;
import github.kasuminova.ssoptimizer.common.render.queue.FrameFenceImpl;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import github.kasuminova.ssoptimizer.common.render.queue.SignalFenceCommand;
import github.kasuminova.ssoptimizer.common.render.queue.WaitFenceCommand;

/**
 * org.lwjgl.opengl.GL32 的 bridge 镜像（fence sync 三件套）。
 * <p>
 * 动机：BoxUtil 用 glFenceSync/glWaitSync/glDeleteSync 做跨上下文的 GPU 命令流
 * 可见性协调。aux-context 折叠进单渲染线程后（见 {@link SharedDrawable}），fence
 * 退化为队列内会合点：
 * <ul>
 *   <li>{@link #glFenceSync} 录制 {@link SignalFenceCommand}（渲染线程执行到该
 *       点时完成 fence——此前的渲染命令已全部进入执行流），并立即返回关联的
 *       {@link GLSync} 句柄；</li>
 *   <li>{@link #glWaitSync} 录制 {@link WaitFenceCommand}，执行时检查 fence
 *       是否已 signal：已 signal 直接放行，未 signal 则帧悬挂续跑（渲染线程
 *       不阻塞，协议细节见 WaitFenceCommand 类 javadoc）；</li>
 *   <li>{@link #glDeleteSync} 只做句柄失效标记：fence 是纯 Java 对象，无真实
 *       GPU 资源需要释放。</li>
 * </ul>
 * 骨架阶段 SignalFenceCommand 的命令体只完成 fence；接入游戏后如需与真实 GPU
 * 时间线对齐（性能分析等），再在命令体内追加真实 glFenceSync 调用。
 */
public final class GL32 {
    private GL32() {
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

    /**
     * 录制 fence 信号命令并返回关联句柄。句柄立即有效（指向已入队的会合点），
     * 无需阻塞等待渲染线程。
     * <p>
     * fence 同时登记到当前帧（{@link RenderFrame#addFence}）：帧执行失败丢弃
     * 剩余命令时，队列会强制完成登记过的 fence——否则信号命令随失败帧被丢弃，
     * 等待该 fence 的悬挂续跑任务将永久自旋。
     *
     * @param condition LWJGL2 语义下固定为 GL_SYNC_GPU_COMMANDS_COMPLETE，原样保留
     * @param flags     保留参数，LWJGL2 语义下必须为 0
     * @return 关联本次 fence 的不透明句柄
     */
    public static GLSync glFenceSync(int condition, int flags) {
        FrameFence fence = new FrameFenceImpl();
        RenderQueue queue = BridgeSupport.queue();
        queue.currentFrame().addFence(fence);
        queue.submit(new SignalFenceCommand(fence));
        return new GLSync(fence);
    }

    /**
     * 录制 fence 等待命令。{@code sync} 必须来自本 bridge 的
     * {@link #glFenceSync}（ASM 重定向保证模组拿到的只有本 bridge 的句柄）。
     *
     * @param sync    glFenceSync 返回的句柄
     * @param flags   保留参数，LWJGL2 语义下必须为 0
     * @param timeout LWJGL2 语义下固定为 GL_TIMEOUT_IGNORED，原样保留
     */
    public static void glWaitSync(GLSync sync, int flags, long timeout) {
        BridgeSupport.enqueue(new WaitFenceCommand(sync.fence()));
    }

    /**
     * 标记句柄失效。折叠模型下 sync 对象是纯 Java 会合点，无队列命令产生；
     * 幂等，与真实 glDeleteSync 对同一 sync 多次调用未定义的语义不冲突。
     */
    public static void glDeleteSync(GLSync sync) {
        sync.markDeleted();
    }

    // ------------------------------------------------------------------
    // 盘点补面：64 位 getter（BoxUtil 等的健康校验/能力探测）
    // ------------------------------------------------------------------

    /** 64 位 getter：阻塞通道取回。 */
    public static long glGetInteger64(int pname) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname));
    }

    /** 带索引 64 位 getter：阻塞通道取回。 */
    public static long glGetInteger64(int pname, int index) {
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.GL32.glGetInteger64(pname, index));
    }
}
