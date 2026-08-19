package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.MutableStat;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SyncStatBonusMap} 并发与兼容语义验证：
 * 并发 put+values 迭代不产生 null 元素/不抛 CME（结构安全），
 * 视图快照不随后续写变化，null key/value 与原版 LinkedHashMap 一致。
 */
class SyncStatBonusMapTest {

    private static MutableStat.StatMod mod(String source, float value) {
        return new MutableStat.StatMod(source, MutableStat.StatModType.FLAT, value, null);
    }

    @Test
    void concurrentPutAndValuesSnapshotNeverYieldNullOrCme() throws Exception {
        SyncStatBonusMap map = new SyncStatBonusMap();
        int writers = 4;
        int writesPerWriter = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < writers; w++) {
                final int writer = w;
                futures.add(pool.submit(() -> {
                    await(start);
                    for (int i = 0; i < writesPerWriter; i++) {
                        map.put("w" + writer + "_" + i, mod("w" + writer, i));
                    }
                    return null;
                }));
            }
            futures.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < writesPerWriter * writers / 4; i++) {
                    Collection<MutableStat.StatMod> snapshot = map.values();
                    for (MutableStat.StatMod m : snapshot) {
                        if (m == null) {
                            throw new IllegalStateException("快照中出现 null 元素（并发写损坏）");
                        }
                    }
                }
                return null;
            }));
            start.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
            assertEquals(writers * writesPerWriter, map.size(), "并发写后元素不得丢失");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void viewsAreSnapshotsDetachedFromLaterWrites() {
        SyncStatBonusMap map = new SyncStatBonusMap();
        map.put("a", mod("a", 1.0f));
        Collection<MutableStat.StatMod> values = map.values();
        java.util.Set<String> keys = map.keySet();
        java.util.Set<Map.Entry<String, MutableStat.StatMod>> entries = map.entrySet();
        map.put("b", mod("b", 2.0f));
        assertEquals(1, values.size(), "values() 快照不得随后续写入变化");
        assertEquals(1, keys.size(), "keySet() 快照不得随后续写入变化");
        assertEquals(1, entries.size(), "entrySet() 快照不得随后续写入变化");
        assertEquals(2, map.size());
    }

    @Test
    void nullKeyAndValueToleratedLikeVanilla() {
        SyncStatBonusMap map = new SyncStatBonusMap();
        assertDoesNotThrow(() -> {
            map.put(null, mod("nullKey", 1.0f));
            map.put("nullValue", null);
        });
        assertNull(map.get("nullValue"));
        assertTrue(map.containsKey(null), "null key 应可写入（原版 LinkedHashMap 语义）");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
