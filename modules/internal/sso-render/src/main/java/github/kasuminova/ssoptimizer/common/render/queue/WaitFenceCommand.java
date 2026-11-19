package github.kasuminova.ssoptimizer.common.render.queue;

import org.apache.log4j.Logger;

import java.util.concurrent.atomic.AtomicBoolean;

/** fence 未完成时悬挂余下命令而不阻塞；放行后调用 onSignaled，续跑时允许重复调用。 */
public final class WaitFenceCommand implements GlCommand {
    private static final Logger LOGGER = Logger.getLogger(WaitFenceCommand.class);

    private final FrameFence fence;
    private final Runnable onSignaled;
    private final AtomicBoolean suspendWarned = new AtomicBoolean();

    public WaitFenceCommand(FrameFence fence) {
        this(fence, null);
    }

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
            LOGGER.warn("[SSOptimizer] 渲染 fence 尚未完成，剩余帧命令将在 fence 完成后继续执行");
        }
        throw SuspendFrameException.INSTANCE;
    }
}
