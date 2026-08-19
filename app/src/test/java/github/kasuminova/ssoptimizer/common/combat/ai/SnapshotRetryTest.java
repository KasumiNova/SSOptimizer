package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotRetry} 的并发契约（StatBonus.recompute / FiringSolutionEval
 * 武器迭代的快照语义）：快照拷贝期间集合被并发写 → 有界重试后成功；正常路径
 * 零重试；持续写（重试耗尽）→ 重抛 CME（不吞异常）。
 */
class SnapshotRetryTest {

    @Test
    void succeedsOnTransientConcurrentWrite() {
        List<String> shared = new ArrayList<>(List.of("a", "b", "c"));
        // 快照源：每次重试重新取活引用（模拟 map.values()）；并发写在快照后发生
        List<String> snapshot = SnapshotRetry.snapshotWithRetry(() -> {
            List<String> copy = new ArrayList<>(shared);
            shared.add("concurrent-write");
            return copy;
        }, "test-mods");
        assertTrue(snapshot.contains("a") && snapshot.contains("b") && snapshot.contains("c"),
                "快照必须包含迭代开始时刻的元素");
    }

    @Test
    void retriesUntilConcurrentWriteSettles() {
        // 前几次快照撞上并发写（CME），后续写停止后成功——验证有界重试语义
        List<String> shared = new ArrayList<>(List.of("a", "b"));
        AtomicInteger snapshotAttempts = new AtomicInteger();
        AtomicInteger writeOnce = new AtomicInteger(1);

        List<String> snapshot = SnapshotRetry.snapshotWithRetry(() -> {
            snapshotAttempts.incrementAndGet();
            if (writeOnce.getAndDecrement() > 0) {
                shared.add("late-write");
                throw new ConcurrentModificationException("simulated transient write");
            }
            return new ArrayList<>(shared);
        }, "test-mods");

        assertTrue(snapshotAttempts.get() > 1, "瞬时并发写必须触发重试");
        assertEquals(3, snapshot.size(), "重试成功后快照必须完整");
    }

    @Test
    void rethrowsAfterExhaustingRetries() {
        // 持续并发写：每次重试都撞 CME → 重试耗尽后必须重抛（禁止吞异常）
        assertThrows(ConcurrentModificationException.class,
                () -> SnapshotRetry.snapshotWithRetry(() -> {
                    throw new ConcurrentModificationException("simulated persistent write");
                }, "test-mods"));
    }

    @Test
    void succeedsFirstTryWithoutContention() {
        // 正常路径（无并发写）：一次成功
        List<String> shared = new ArrayList<>(List.of("x", "y"));
        List<String> snapshot = SnapshotRetry.snapshotWithRetry(() -> shared, "test-mods");
        assertEquals(2, snapshot.size(), "正常路径快照必须完整");
        assertEquals(List.of("x", "y"), snapshot);
    }
}
