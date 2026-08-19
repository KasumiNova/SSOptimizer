package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * glWaitSync 的录制命令：执行时检查携带的 fence 是否已 signal。
 * <p>
 * 语义（帧悬挂协议，替代早期的无条件阻塞）：fence 已 signal 则直接放行；
 * 未 signal 则抛出 {@link SuspendFrameException}，由渲染队列把本命令起的剩余
 * 命令打包续跑——渲染线程绝不阻塞等 fence。阻塞等 fence 会与 BoxUtil 的
 * Phaser 协调形成三方死锁（main 等帧完成 → 渲染线程等 fence → BoxUtil 等
 * main 到达 Phaser），悬挂后本帧正常完成、main 被释放推进，fence 信号在后续
 * 帧到达时续跑任务会合。
 * <p>
 * 稳态零开销：BoxUtil 的 fence 产消经 AtomicReference 发布，glWaitSync 的录制
 * 必然 happens-after 对应 glFenceSync 的录制，同帧内 signal 命令先于 wait 执行，
 * 执行到本命令时 fence 必然已 signal。
 */
public final class WaitFenceCommand implements GlCommand {
    private static final Logger LOGGER = Logger.getLogger(WaitFenceCommand.class);

    private final FrameFence fence;
    /** 每个 wait 命令实例只在首次悬挂时 warn 一次（命令实例随续跑任务复用）。 */
    private final AtomicBoolean suspendWarned = new AtomicBoolean();

    /**
     * @param fence 执行前要检查已 signal 的 fence
     */
    public WaitFenceCommand(FrameFence fence) {
        this.fence = fence;
    }

    @Override
    public void execute() {
        if (fence.isSignaled()) {
            return;
        }
        if (suspendWarned.compareAndSet(false, true)) {
            LOGGER.warn("[SSOptimizer] frame fence 尚未 signal，本帧余下命令将悬挂续跑"
                    + "（BoxUtil 类跨线程 fence 产消滞后的恢复路径；稳态不应出现本日志）");
        }
        throw SuspendFrameException.INSTANCE;
    }
}
