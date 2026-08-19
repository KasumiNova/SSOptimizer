package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * glFenceSync 的录制命令：渲染线程执行到本命令时完成携带的 fence。
 * <p>
 * 语义对应 GPU 命令流上的 fence 插入点：此前的渲染命令已全部进入 GPU 队列，
 * 等待该 fence 的 {@link WaitFenceCommand}（可能记录在更晚的帧或来自 aux-context
 * 生产者线程）自此可以继续。骨架阶段命令体只完成 {@link FrameFence}；接入游戏后
 * 由 bridge 的 glFenceSync 负责在命令体内追加真实 {@code glFenceSync} 调用。
 */
public final class SignalFenceCommand implements GlCommand {
    private final FrameFence fence;

    /**
     * @param fence 渲染线程执行到本命令时要完成的 fence
     */
    public SignalFenceCommand(FrameFence fence) {
        this.fence = fence;
    }

    @Override
    public void execute() {
        fence.signal();
    }
}
