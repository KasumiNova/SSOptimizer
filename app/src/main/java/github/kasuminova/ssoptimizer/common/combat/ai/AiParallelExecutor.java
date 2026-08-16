package github.kasuminova.ssoptimizer.common.combat.ai;

/**
 * AI 并行执行线程池。
 * <p>
 * 动机：原版战斗引擎在 {@code CombatEngine.advanceInner} 内串行调用每个实体的
 * {@code AI.advance(float)}，大规模战场下舰船 AI 是 advance 阶段的主要开销之一。
 * 本接口把白名单内的原版舰船 AI（BasicShipAI / FighterAI）分发给工作线程并行执行，
 * 帧末由 {@link #awaitAll()} 屏障保证全部任务完成后才继续后续实体 advance。
 */
public interface AiParallelExecutor {
    /**
     * 投递一个 AI 任务。
     *
     * @param task      AI advance 任务
     * @param stripeKey 分组键；相同键的任务保证在同一线程串行执行，{@code null} 表示自由分发
     */
    void submit(Runnable task, Object stripeKey);

    /**
     * 帧内屏障：阻塞至所有已投递任务完成；若有任务抛出异常，收集后以
     * {@link RuntimeException} 在调用线程（主线程）重新抛出。
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
}
