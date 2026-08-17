package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * glWaitSync 的录制命令：执行前先阻塞等待携带的 fence 完成。
 * <p>
 * 动机（BoxUtil 骨架钩子之一）：glWaitSync 可能被记录到对应 glFenceSync 的
 * 信号命令之前执行（跨线程录制顺序不保证），因此本命令不假设队列顺序，
 * 执行时无条件阻塞到 fence 完成——信号无论来自渲染流上的
 * {@link SignalFenceCommand} 还是 CPU 侧生产者线程直接 signal，都能正确会合，
 * 避免渲染线程空等死锁。
 */
public final class WaitFenceCommand implements GlCommand {
    private final FrameFence fence;

    /**
     * @param fence 执行前要等待完成的 fence
     */
    public WaitFenceCommand(FrameFence fence) {
        this.fence = fence;
    }

    @Override
    public void execute() {
        try {
            fence.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("[SSOptimizer] 渲染线程等待 frame fence 时被中断", e);
        }
    }
}
