package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.combat.ai.AI;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

/**
 * 池化的舰船 AI 推进任务（{@code ai.advance(amount)} 闭包的对象化）。
 * <p>
 * 动机：{@link ParallelAiDispatcher#dispatch} 原实现每船每帧分配一个 lambda
 * 闭包（cpu profile：dispatch 1,160 样本中约一半为分配与投递开销），池化后
 * 稳态零分配。借出发生在主线程分发循环，归还在工作线程任务成功执行后——
 * 恰好 MPMC 语义（多借出者无、单借出点多归还者），用 JCTools 无界数组队列，
 * 与 bridge 侧 {@code CommandPool} 同选型。
 * <p>
 * 生命周期约束：任务异常时不归还（失败任务被执行器记录在 failures 中等待主线程
 * 串行重跑，字段必须保持原值），直接交 GC——见
 * {@link FrameParallelExecutor.PooledTask} 的回收约定。
 */
final class PooledAiTask implements Runnable, FrameParallelExecutor.PooledTask {
    /** 全局空闲池（借出：主线程；归还：各工作线程）。 */
    private static final MpmcUnboundedXaddArrayQueue<PooledAiTask> POOL =
            new MpmcUnboundedXaddArrayQueue<>(1024);

    private AI ai;
    private float amount;

    private PooledAiTask(final AI ai, final float amount) {
        this.ai = ai;
        this.amount = amount;
    }

    /** 借出任务并绑定本帧参数（池空则新建）。 */
    static PooledAiTask acquire(final AI ai, final float amount) {
        final PooledAiTask pooled = POOL.poll();
        if (pooled != null) {
            pooled.ai = ai;
            pooled.amount = amount;
            return pooled;
        }
        return new PooledAiTask(ai, amount);
    }

    /**
     * 任务成功执行后由工作线程归还（{@link FrameParallelExecutor.PooledTask} 约定）。
     * 清空 AI 引用避免池内滞留战斗实体堆引用。
     */
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
