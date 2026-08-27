package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.MissileAIPlugin;
import com.fs.starfarer.combat.ai.AI;
import com.fs.starfarer.combat.ai.BasicShipAI;
import com.fs.starfarer.combat.ai.FighterAI;
import com.fs.starfarer.combat.entities.Missile;
import github.kasuminova.ssoptimizer.common.concurrent.FrameParallelExecutor;
import github.kasuminova.ssoptimizer.common.concurrent.SharedFrameWorkers;
import github.kasuminova.ssoptimizer.mixin.accessor.FighterAIAccessor;

/**
 * 并行舰船 AI 调度门面，供 {@code CombatEngineAiParallelMixin} 织入
 * {@code CombatEngine.advanceInner} 后直接调用。
 * <p>
 * 织入效果：原版 AI 循环中的 {@code AI.advance(float)} 调用被替换为
 * {@link #dispatch(AI, float)}，循环结束（"Advancing entities" 段之前）插入
 * {@link #awaitAll()} 帧内屏障。
 * <p>
 * 白名单策略：仅精确类型为 {@link BasicShipAI} / {@link FighterAI} 的原版舰船 AI
 * 入队并行；其余 AI（导弹引信 AI、以及 AI Tweaks 等模组注入的自定义 ShipAIPlugin）
 * 在调用点内联串行执行，行为与原版完全一致——模组兼容由精确类型匹配保证，
 * 模组即便继承/替换原版 AI 也只会落到内联路径。
 * <p>
 * 战机编队约束：{@link FighterAI} 经 Mixin Accessor（{@code FighterAIAccessor}）暴露
 * 私有 {@code wing} 字段，同编队（共享 {@code FighterWing}）的战机任务固定到同一
 * 工作线程串行执行。
 * <p>
 * 开关：{@code -Dssoptimizer.ai.parallel=false} 运行期整体关闭（全部内联串行）。
 * 工作线程数由共享池统一属性 {@code ssoptimizer.workers.threads} 配置，
 * 见 {@link SharedFrameWorkers}。
 */
public final class ParallelAiDispatcher {
    public static final String ENABLED_PROPERTY = "ssoptimizer.ai.parallel";

    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty(ENABLED_PROPERTY, "true"));
    private static final FrameParallelExecutor EXECUTOR = ENABLED ? SharedFrameWorkers.get() : null;

    /**
     * 模组 AI/脚本全局串行锁。
     * <p>
     * 用途：模组 jar 由游戏自带 URLClassLoader 加载，不经 LaunchClassLoader 变换链，
     * 无法直接 Mixin 修复模组类；模组代码普遍使用 LazyLib {@code CombatCache} 等非线程
     * 安全的共享静态状态。工作线程上的模组系统脚本（ShipSystemScriptGuardMixin）与
     * 主线程内联执行的模组 AI（本类 dispatch 内联路径）必须持有同一把锁互斥，
     * 否则长程运行必现 ConcurrentModificationException。
     */
    public static final Object MOD_SCRIPT_LOCK = new Object();

    private ParallelAiDispatcher() {
    }

    /**
     * AI 循环织入点：白名单内 AI 投递到线程池，其余内联执行。
     * 栈签名与 {@code AI.advance(F)V} 调用点完全一致（{@code (AI, F) -> void}）。
     * <p>
     * 内联路径在主线程上执行时并行窗口仍未关闭（worker 正在跑舰船 AI），
     * 因此模组 AI（含原版 GuidedMissileAIWrapper 包装的模组导弹插件）必须持有
     * {@link #MOD_SCRIPT_LOCK} 与 worker 上的模组脚本互斥。
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

    /**
     * 帧内屏障织入点：等待本帧全部并行 AI 任务完成，推进线程本地帧号，
     * 并汇总任务异常在主线程重新抛出。
     */
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
     * 供 {@link AutofireBatchRunner} 等同包批处理复用底层执行器（共享池实例）。
     * 注意：直接使用返回执行器的 {@code awaitAll} 不会推进线程本地帧号，
     * 帧号推进仅由 {@link #awaitAll()} 负责。
     *
     * @return 执行器实例；AI 并行关闭时为 null
     */
    static FrameParallelExecutor executor() {
        return EXECUTOR;
    }

    /**
     * 工作线程守卫：当前线程是否为共享池工作线程（Profiler 等共享静态状态据此守卫）。
     * 委托 {@link SharedFrameWorkers#isWorkerThread()}——AI 并行关闭而共享池已被
     * 其他域（如市场推进）创建时，守卫仍能识别工作线程。
     */
    public static boolean isWorkerThread() {
        return SharedFrameWorkers.isWorkerThread();
    }

    /**
     * 白名单判断：仅原版舰船 AI 的精确类型（子类与模组实现一律内联）。
     */
    private static boolean isVanillaShipAi(AI ai) {
        Class<?> type = ai.getClass();
        return type == BasicShipAI.class || type == FighterAI.class;
    }

    /**
     * 模组 AI 判断：非 {@code com.fs.starfarer.} 包的 AI 直接判定为模组；
     * 原版 {@code Missile$GuidedMissileAIWrapper} / {@code Missile$MissileAIWrapper}
     * 需解开包装检查内部插件（模组导弹 AI 由原版包装器承载，长程基准实测其在主线程
     * 内联执行时与 worker 上的模组系统脚本并发踩踏 LazyLib CombatCache）。
     */
    private static boolean isModAi(AI ai) {
        if (!ai.getClass().getName().startsWith("com.fs.starfarer.")) {
            return true;
        }
        final MissileAIPlugin plugin;
        if (ai instanceof Missile.GuidedMissileAIWrapper guided) {
            plugin = guided.getAI();
        } else if (ai instanceof Missile.MissileAIWrapper wrapper) {
            plugin = wrapper.getAI();
        } else {
            return false;
        }
        return plugin != null && !plugin.getClass().getName().startsWith("com.fs.starfarer.");
    }
}
