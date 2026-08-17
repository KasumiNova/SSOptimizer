package github.kasuminova.ssoptimizer.common.render.queue;

import java.util.concurrent.CountDownLatch;

/**
 * {@link FrameFence} 的默认实现，基于 {@link CountDownLatch}（计数 1）。
 * <p>
 * 单次语义：fence 不可重复使用，与 glFenceSync 产生的 sync 对象生命周期一致
 * （glDeleteSync 语义由后续的 sync 对象管理方案负责，骨架阶段 fence 随帧回收）。
 */
public final class FrameFenceImpl implements FrameFence {
    private final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void signal() {
        latch.countDown();
    }

    @Override
    public void await() throws InterruptedException {
        latch.await();
    }

    @Override
    public boolean isSignaled() {
        return latch.getCount() == 0;
    }
}
