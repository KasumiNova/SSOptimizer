package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.concurrent.atomic.AtomicBoolean;

public final class FrameFenceImpl implements FrameFence {
    private final AtomicBoolean signaled = new AtomicBoolean();

    @Override
    public void signal() {
        signaled.set(true);
    }

    @Override
    public boolean isSignaled() {
        return signaled.get();
    }
}
