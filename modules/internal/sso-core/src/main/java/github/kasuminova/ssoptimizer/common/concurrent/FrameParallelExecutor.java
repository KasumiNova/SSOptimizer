package github.kasuminova.ssoptimizer.common.concurrent;

/**
 * 主线程投递、工作线程执行的帧内任务执行器。
 * {@link #submit} 与 {@link #awaitAll()} 仅限游戏逻辑线程调用，每帧任务必须在同帧屏障内完成。
 */
public interface FrameParallelExecutor {
    /**
     * 投递一个任务。
     *
     * @param task      任务体
     * @param stripeKey 分组键；相同键的任务保证在同一工作线程串行执行，
     *                  {@code null} 表示自由分发（轮询）
     */
    void submit(Runnable task, Object stripeKey);

    /**
     * 帧内屏障：阻塞至所有已投递任务完成；若有任务抛出异常，先收集并在调用线程
     * （主线程）串行重跑失败任务一次，重跑仍失败则以 {@link RuntimeException}
     * 在调用线程重新抛出。
     */
    void awaitAll();

    boolean isWorkerThread();

    int threadCount();

    /** 成功任务由工作线程回收；失败任务保留字段，等待主线程串行重跑。 */
    interface PooledTask {
        void recycle();
    }
}
