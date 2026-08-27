package github.kasuminova.ssoptimizer.common.concurrent;

import org.apache.log4j.Logger;

/**
 * 全局共享帧内工作池门面。
 * <p>
 * 动机：战斗 AI（sso-ai）与经济体市场推进（sso-core）曾各自持有独立的
 * {@link FrameParallelExecutorImpl} 实例，同一份「帧内屏障工作池」逻辑维护两份
 * 配置与两份线程；两者的调用域互斥（战斗帧 vs 生涯帧，不同游戏阶段，均由主线程
 * 驱动），合并为单一共享池不引入同帧竞争，线程总量减半。
 * <p>
 * 使用约束：本池仅供<b>帧内屏障 CPU 密集任务</b>使用——主线程投递后必须在同一帧内
 * 经 {@link FrameParallelExecutor#awaitAll()} 屏障等待完成；禁止提交阻塞型或长耗时
 * 任务（会占住 worker 拖垮整帧屏障）。IO 阻塞/一次性批任务走 {@link VtWorkers}。
 * <p>
 * stripeKey 语义：携带相同 stripeKey 的任务按身份哈希固定到同一工作线程串行执行
 * （如战机编队共享 {@code FighterWing} 状态），{@code null} 键轮询分发；语义与
 * 独立池时期完全一致。
 * <p>
 * 互斥前提：当前 AI（战斗帧）与 Econ（生涯帧）的投递域在不同游戏阶段互斥，共享池
 * 任意时刻只服务一个域。未来若出现同帧并发投递的两域任务，它们会竞争同一批
 * worker——不会出错（屏障语义不变），最坏情况下并行度退化接近串行。
 * <p>
 * 线程数：统一由 {@code -Dssoptimizer.workers.threads=N} 配置，默认
 * {@code max(cores-1, 1)}（保留 1 核给主线程/渲染线程，避免 oversubscription）；
 * 非法取值按默认处理并记 WARN。工作线程名 {@code SSOptimizer-Shared-Worker-N}。
 * <p>
 * 懒创建：首个使用方触发创建（双重检查锁），全程只创建一个实例。
 */
public final class SharedFrameWorkers {
    /** 共享工作线程数系统属性名。 */
    public static final String THREADS_PROPERTY = "ssoptimizer.workers.threads";

    private static final Logger LOGGER = Logger.getLogger(SharedFrameWorkers.class);

    private static volatile FrameParallelExecutorImpl instance;

    private SharedFrameWorkers() {
    }

    /**
     * 获取共享帧内工作池，首个调用方触发创建。
     *
     * @return 全局唯一的共享执行器实例
     */
    public static FrameParallelExecutor get() {
        FrameParallelExecutorImpl local = instance;
        if (local == null) {
            synchronized (SharedFrameWorkers.class) {
                local = instance;
                if (local == null) {
                    final int threads = resolveThreadCount(System.getProperty(THREADS_PROPERTY));
                    LOGGER.info("[SSOptimizer] Shared frame workers created with " + threads + " thread(s)");
                    local = new FrameParallelExecutorImpl("Shared", threads);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * 工作线程守卫：当前线程是否为共享池工作线程。
     * <p>
     * 供 Profiler 等共享静态状态的守卫（{@code ProfilerWorkerGuardMixin} /
     * {@code ShipSystemScriptGuardMixin}）使用；池未创建时不存在任何工作线程，
     * 恒返回 {@code false}。
     *
     * @return 当前线程是共享池工作线程时返回 true
     */
    public static boolean isWorkerThread() {
        final FrameParallelExecutorImpl local = instance;
        return local != null && local.isWorkerThread();
    }

    /**
     * 解析共享工作线程数属性值。
     * <p>
     * 未设置时返回默认值 {@code max(cores-1, 1)}；非法取值（不可解析或 <1）按默认
     * 处理并记 WARN 日志。生产环境仅在共享池创建时调用一次，WARN 最多出现一次。
     *
     * @param raw 属性原始值（可为 {@code null}）
     * @return 生效的工作线程数（≥1）
     */
    static int resolveThreadCount(final String raw) {
        final int defaultCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        if (raw == null) {
            return defaultCount;
        }

        final int parsed;
        try {
            parsed = Integer.parseInt(raw);
        } catch (final NumberFormatException e) {
            LOGGER.warn("[SSOptimizer] " + THREADS_PROPERTY + " 取值 \"" + raw
                    + "\" 无法解析，按默认 " + defaultCount + " 处理", e);
            return defaultCount;
        }

        if (parsed < 1) {
            LOGGER.warn("[SSOptimizer] " + THREADS_PROPERTY + " 取值 " + parsed
                    + " 非法（必须 ≥1），按默认 " + defaultCount + " 处理");
            return defaultCount;
        }
        return parsed;
    }
}
