package github.kasuminova.ssoptimizer.common.combat.ai;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 并行 AI 共享集合迭代的快照工具：并发写期间对活引用集合做快照拷贝时捕获
 * {@link ConcurrentModificationException} 并有界重试（正常路径零 CME 零开销）。
 * <p>
 * 用途：AI worker 线程（FiringSolutionEval/BasicShipAI/StatBonus.recompute 等）
 * 迭代游戏共享集合（mod 表、武器列表）时，并行窗口期另一线程（主线程 mod
 * 系统更新、其他 worker）可能并发写——裸迭代随并发写抛 CME（压测实测崩溃）。
 * 快照 = 迭代开始时刻的组合；集合写是瞬时的，重试通常立即成功。
 * <p>
 * 不吞异常：重试上界 {@value #MAX_RETRIES} 次后仍 CME 时重抛并记 error——
 * 集合持续被并发写是必须暴露的不变量破坏。竞态发生时限频 warn（每
 * {@value #LOG_INTERVAL_MS}ms 至多一次），防止持续竞态日志洪泛。
 */
public final class SnapshotRetry {
    /** 快照创建 CME 的有界重试次数。 */
    static final int MAX_RETRIES = 4;
    /** CME 限频日志间隔（毫秒）。 */
    static final long LOG_INTERVAL_MS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(SnapshotRetry.class);
    private static final AtomicLong LAST_CME_LOG_MS = new AtomicLong();

    private SnapshotRetry() {
    }

    /**
     * 对 {@code source} 做快照拷贝；拷贝期间并发写抛 CME 时有界重试。
     *
     * @param source 活引用集合（如 {@code map.values()}）；每次重试重新获取
     *               以保证新迭代器看到最新状态
     * @param what   快照对象的描述（日志用，如 "StatBonus mods"/"weapons"）
     * @param <T>    元素类型
     * @return 快照拷贝
     */
    public static <T> List<T> snapshotWithRetry(Supplier<Collection<T>> source, String what) {
        for (int attempt = 0; ; attempt++) {
            try {
                return new ArrayList<>(source.get());
            } catch (ConcurrentModificationException e) {
                if (attempt >= MAX_RETRIES) {
                    LOGGER.error("[SSOptimizer] " + what + " 快照在重试 " + MAX_RETRIES
                            + " 次后仍被并发修改，放弃快照（集合持续被写，属必须暴露的不变量破坏）", e);
                    throw e;
                }
                logCmeOnce(what);
            }
        }
    }

    /** 限频记录 CME 竞态（每次竞态说明一次快照重试发生）。 */
    private static void logCmeOnce(String what) {
        long now = System.currentTimeMillis();
        long last = LAST_CME_LOG_MS.get();
        if (now - last >= LOG_INTERVAL_MS && LAST_CME_LOG_MS.compareAndSet(last, now)) {
            LOGGER.warn("[SSOptimizer] " + what + " 快照遇并发修改，重试（竞态为瞬时写，重试通常立即成功）");
        }
    }
}
