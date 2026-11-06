package github.kasuminova.ssoptimizer.common.render.queue;

/** 先执行 onSignal 创建真实 sync，再完成 fence，保证等待方能读取已附着的句柄。 */
public final class SignalFenceCommand implements GlCommand {
    private final FrameFence fence;
    private final Runnable onSignal;

    public SignalFenceCommand(FrameFence fence) {
        this(fence, null);
    }

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
