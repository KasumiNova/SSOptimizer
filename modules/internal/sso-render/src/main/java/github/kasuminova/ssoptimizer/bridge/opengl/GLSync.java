package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.FrameFence;

import java.util.concurrent.atomic.AtomicBoolean;

public final class GLSync {
    private final FrameFence fence;
    private final AtomicBoolean deleted = new AtomicBoolean();
    private volatile Object real;

    GLSync(FrameFence fence) {
        this.fence = fence;
    }

    GLSync(FrameFence fence, Object real) {
        this.fence = fence;
        this.real = real;
    }

    FrameFence fence() {
        return fence;
    }

    Object realSync() {
        return real;
    }

    /** 必须在 fence.signal() 之前调用。 */
    void attachReal(Object real) {
        this.real = real;
    }

    void markDeleted() {
        deleted.set(true);
    }

    public boolean isDeleted() {
        return deleted.get();
    }
}
