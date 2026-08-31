package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * glFenceSync 的录制命令：渲染线程执行到本命令时完成携带的 fence。
 * <p>
 * 语义对应 GPU 命令流上的 fence 插入点：此前的渲染命令已全部进入 GPU 队列，
 * 等待该 fence 的 {@link WaitFenceCommand}（可能记录在更晚的帧或来自 aux-context
 * 生产者线程）自此可以继续。SharedDrawable 解折叠后，bridge 的 glFenceSync 经
 * {@link #onSignal} 钩子在本命令体内追加真实 {@code glFenceSync} 调用（真实
 * sync 对象的创建必须落在命令流序列点上，且先于 {@link FrameFence#signal()}，
 * 使 latch 放行的等待方一定能读到已附着的真实 sync）。
 */
public final class SignalFenceCommand implements GlCommand {
    private final FrameFence fence;
    /** 信号前的真实 GL 钩子（创建真实 sync 并附着句柄）；无真实 GPU 序需求时为 null。 */
    private final Runnable onSignal;

    /**
     * @param fence 渲染线程执行到本命令时要完成的 fence
     */
    public SignalFenceCommand(FrameFence fence) {
        this(fence, null);
    }

    /**
     * @param fence    渲染线程执行到本命令时要完成的 fence
     * @param onSignal signal 前执行的真实 GL 钩子（可为 null）
     */
    public SignalFenceCommand(FrameFence fence, Runnable onSignal) {
        this.fence = fence;
        this.onSignal = onSignal;
    }

    @Override
    public void execute() {
        if (onSignal != null) {
            onSignal.run();
        }
        fence.signal();
    }
}
