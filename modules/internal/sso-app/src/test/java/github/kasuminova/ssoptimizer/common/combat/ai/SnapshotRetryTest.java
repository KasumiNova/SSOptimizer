package github.kasuminova.ssoptimizer.common.combat.ai;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotRetryTest {

    @Test
    void succeedsOnTransientConcurrentWrite() {
        List<String> shared = new ArrayList<>(List.of("a", "b", "c"));
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
        assertThrows(ConcurrentModificationException.class,
                () -> SnapshotRetry.snapshotWithRetry(() -> {
                    throw new ConcurrentModificationException("simulated persistent write");
                }, "test-mods"));
    }

    @Test
    void succeedsFirstTryWithoutContention() {
        List<String> shared = new ArrayList<>(List.of("x", "y"));
        List<String> snapshot = SnapshotRetry.snapshotWithRetry(() -> shared, "test-mods");
        assertEquals(2, snapshot.size(), "正常路径快照必须完整");
        assertEquals(List.of("x", "y"), snapshot);
    }
}
