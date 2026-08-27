package github.kasuminova.ssoptimizer.common.concurrent;

/**
 * 帧内并行任务执行器。
 * <p>
 * 动机：游戏主线程的逐实体 advance 循环（战斗 AI、经济体市场推进等）在大规模
 * 场景下是主线程热点；实体间状态相互独立的部分可分发到工作线程并行执行，
 * 帧末由 {@link #awaitAll()} 屏障保证全部任务完成后主线程才继续后续逻辑。
 * 屏障不跨帧：每帧投递的任务必须在同一帧内全部完成。
 * <p>
 * 线程模型：{@link #submit} 与 {@link #awaitAll()} 仅由主线程（游戏逻辑线程）
 * 调用，任务体在工作线程执行。
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

    /**
     * @return 当前线程是否为本池的工作线程（用于 Profiler 等共享静态状态的守卫）
     */
    boolean isWorkerThread();

    /**
     * @return 工作线程数
     */
    int threadCount();

    /**
     * 池化任务约定。
     * <p>
     * 工作线程在任务成功执行后回调 {@link #recycle()} 归还池；失败任务不回调——
     * 失败任务被失败列表引用等待主线程串行重跑，字段必须保持原值。
     */
    interface PooledTask {
        /** 任务成功执行后归还池（调用方不得再持有引用）。 */
        void recycle();
    }
}
