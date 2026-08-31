package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * glWaitSync 的录制命令：执行时检查携带的 fence 是否已 signal。
 * <p>
 * 语义（帧悬挂协议，替代早期的无条件阻塞）：fence 已 signal 则放行；
 * 未 signal 则抛出 {@link SuspendFrameException}，由渲染队列把本命令起的剩余
 * 命令打包续跑——渲染线程绝不阻塞等 fence。阻塞等 fence 会与 BoxUtil 的
 * Phaser 协调形成三方死锁（main 等帧完成 → 渲染线程等 fence → BoxUtil 等
 * main 到达 Phaser），悬挂后本帧正常完成、main 被释放推进，fence 信号在后续
 * 帧到达时续跑任务会合。
 * <p>
 * 放行后经 {@link #onSignaled} 钩子追加真实 {@code glWaitSync}（SharedDrawable
 * 解折叠后的跨上下文 GPU 序：aux 原生线程产出的真实 sync 对象必须在主上下文
 * 命令流上建立服务端等待，主上下文后续命令才不会越过 aux 的 GPU 工作）。
 * 钩子在续跑重执行时可能再次触发——真实 glWaitSync 对同一 sync 重复调用合法。
 * <p>
 * 稳态零开销：BoxUtil 的 fence 产消经 AtomicReference 发布，glWaitSync 的录制
 * 必然 happens-after 对应 glFenceSync 的录制，同帧内 signal 命令先于 wait 执行，
 * 执行到本命令时 fence 必然已 signal。
 */
public final class WaitFenceCommand implements GlCommand {
    private static final Logger LOGGER = Logger.getLogger(WaitFenceCommand.class);

    private final FrameFence fence;
    /** 放行后的真实 GL 钩子（真实 glWaitSync）；无真实 GPU 序需求时为 null。 */
    private final Runnable onSignaled;
    /** 每个 wait 命令实例只在首次悬挂时 warn 一次（命令实例随续跑任务复用）。 */
    private final AtomicBoolean suspendWarned = new AtomicBoolean();

    /**
     * @param fence 执行前要检查已 signal 的 fence
     */
    public WaitFenceCommand(FrameFence fence) {
        this(fence, null);
    }

    /**
     * @param fence      执行前要检查已 signal 的 fence
     * @param onSignaled 放行后执行的真实 GL 钩子（可为 null）
     */
    public WaitFenceCommand(FrameFence fence, Runnable onSignaled) {
        this.fence = fence;
        this.onSignaled = onSignaled;
    }

    @Override
    public void execute() {
        if (fence.isSignaled()) {
            if (onSignaled != null) {
                onSignaled.run();
            }
            return;
        }
        if (suspendWarned.compareAndSet(false, true)) {
            LOGGER.warn("[SSOptimizer] frame fence 尚未 signal，本帧余下命令将悬挂续跑"
                    + "（BoxUtil 类跨线程 fence 产消滞后的恢复路径；稳态不应出现本日志）");
        }
        throw SuspendFrameException.INSTANCE;
    }
}
