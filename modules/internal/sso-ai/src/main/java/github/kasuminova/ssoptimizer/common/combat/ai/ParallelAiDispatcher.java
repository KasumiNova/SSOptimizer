package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.combat.ai.AI;
import com.fs.starfarer.combat.ai.BasicShipAI;
import com.fs.starfarer.combat.ai.FighterAI;
import com.fs.starfarer.combat.entities.Missile;
import com.fs.starfarer.combat.entities.Ship;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;
import github.kasuminova.ssoptimizer.mixin.accessor.FighterAIAccessor;

public final class ParallelAiDispatcher {
    public static final String ENABLED_PROPERTY = "ssoptimizer.ai.parallel";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));
    private static final FrameParallelExecutor EXECUTOR = ENABLED ? SharedFrameWorkers.get() : null;

    /** 工作线程的模组系统脚本与主线程内联模组 AI 必须通过此锁互斥。 */
    public static final Object MOD_SCRIPT_LOCK = new Object();

    private ParallelAiDispatcher() {
    }

    /**
     * 白名单外的模组 AI 可能与工作线程上的模组脚本并发执行，因此内联时持有
     * {@link #MOD_SCRIPT_LOCK}。
     */
    public static void dispatch(AI ai, float amount) {
        FrameParallelExecutor executor = EXECUTOR;
        if (executor == null || executor.isWorkerThread()) {
            ai.advance(amount);
            return;
        }
        if (!isVanillaShipAi(ai)) {
            if (isModAi(ai)) {
                synchronized (MOD_SCRIPT_LOCK) {
                    ai.advance(amount);
                }
            } else {
                ai.advance(amount);
            }
            return;
        }
        Object stripeKey = ai instanceof FighterAIAccessor accessor ? accessor.ssoptimizer$getWing() : null;
        executor.submit(PooledAiTask.acquire(ai, amount), stripeKey);
    }

    public static void awaitAll() {
        FrameParallelExecutor executor = EXECUTOR;
        if (executor == null) {
            return;
        }
        try {
            executor.awaitAll();
        } finally {
            AiThreadLocals.nextFrame();
        }
    }

    /**
     * 返回共享执行器，AI 并行关闭时为 {@code null}。
     * 直接调用其 {@code awaitAll} 不会推进 {@link AiThreadLocals} 的帧号。
     */
    static FrameParallelExecutor executor() {
        return EXECUTOR;
    }

    /** 即使 AI 并行关闭，也能识别共享池中其他功能使用的工作线程。 */
    public static boolean isWorkerThread() {
        return SharedFrameWorkers.isWorkerThread();
    }

    private static boolean isVanillaShipAi(AI ai) {
        Class<?> type = ai.getClass();
        return type == BasicShipAI.class || type == FighterAI.class;
    }

    /** 原版 AI 包装器需检查内部插件的实际来源。 */
    private static boolean isModAi(AI ai) {
        if (!ai.getClass().getName().startsWith("com.fs.starfarer.")) {
            return true;
        }
        final Object plugin;
        if (ai instanceof Missile.GuidedMissileAIWrapper guided) {
            plugin = guided.getAI();
        } else if (ai instanceof Missile.MissileAIWrapper wrapper) {
            plugin = wrapper.getAI();
        } else if (ai instanceof Ship.ShipAIWrapper wrapper) {
            plugin = wrapper.getAI();
        } else {
            return false;
        }
        return plugin != null && !plugin.getClass().getName().startsWith("com.fs.starfarer.");
    }
}
