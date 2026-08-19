package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link FrameFence} 的默认实现，基于 {@link AtomicBoolean}。
 * <p>
 * 单次语义：fence 不可重复使用，与 glFenceSync 产生的 sync 对象生命周期一致
 * （glDeleteSync 语义由后续的 sync 对象管理方案负责，骨架阶段 fence 随帧回收）。
 * 等待侧已改为非阻塞悬挂（见 {@link WaitFenceCommand}），实现不再需要 latch。
 */
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
