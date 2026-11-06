package github.kasuminova.ssoptimizer.common.combat.ai;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 为并行 AI 读取的共享集合创建快照，拷贝遇到并发修改时有界重试。
 * 重试耗尽后记录错误并重抛 {@link ConcurrentModificationException}，重试日志按时间限频。
 */
public final class SnapshotRetry {
    static final int MAX_RETRIES = 4;
    static final long LOG_INTERVAL_MS = 5_000L;

    private static final Logger LOGGER = Logger.getLogger(SnapshotRetry.class);
    private static final AtomicLong LAST_CME_LOG_MS = new AtomicLong();

    private SnapshotRetry() {
    }

    /**
     * 每次重试都会重新调用 {@code source}，使新迭代器读取最新集合状态。
     *
     * @param source 活引用集合的供应器
     * @param what   日志中的集合描述
     * @return 独立快照
     * @throws ConcurrentModificationException 重试耗尽后仍发生并发修改时抛出
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

    private static void logCmeOnce(String what) {
        long now = System.currentTimeMillis();
        long last = LAST_CME_LOG_MS.get();
        if (now - last >= LOG_INTERVAL_MS && LAST_CME_LOG_MS.compareAndSet(last, now)) {
            LOGGER.warn("[SSOptimizer] " + what + " 快照遇并发修改，重试（竞态为瞬时写，重试通常立即成功）");
        }
    }
}
