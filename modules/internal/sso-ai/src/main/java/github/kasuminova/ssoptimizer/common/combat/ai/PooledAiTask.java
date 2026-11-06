package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.combat.ai.AI;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

/**
 * 可复用的舰船 AI 推进任务。
 * 仅成功执行后回收；失败任务必须保留参数供执行器串行重跑。
 */
final class PooledAiTask implements Runnable, FrameParallelExecutor.PooledTask {
    private static final MpmcUnboundedXaddArrayQueue<PooledAiTask> POOL =
            new MpmcUnboundedXaddArrayQueue<>(1024);

    private AI ai;
    private float amount;

    private PooledAiTask(final AI ai, final float amount) {
        this.ai = ai;
        this.amount = amount;
    }

    static PooledAiTask acquire(final AI ai, final float amount) {
        final PooledAiTask pooled = POOL.poll();
        if (pooled != null) {
            pooled.ai = ai;
            pooled.amount = amount;
            return pooled;
        }
        return new PooledAiTask(ai, amount);
    }

    @Override
    public void recycle() {
        ai = null;
        POOL.offer(this);
    }

    @Override
    public void run() {
        ai.advance(amount);
    }
}
