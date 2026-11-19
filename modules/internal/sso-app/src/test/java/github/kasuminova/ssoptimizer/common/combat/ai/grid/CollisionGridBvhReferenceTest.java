package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按引用对照 BVH 与原版均匀网格的查询结果。集合等价性由 {@link CollisionGridBvhTest} 覆盖，此处不重复。
 */
class CollisionGridBvhReferenceTest {

    private static final float GRID_MIN = -10000.0f;
    private static final float GRID_MAX = 10000.0f;
    private static final float CELL     = 300.0f;

    @Test
    void equalReferencesCollapseToOneAndSurvivorIsAnInputReference() {
        final VanillaGridReference ref = newRef();
        final CollisionGridBvhImpl bvh = newBvh();
        final List<EqualBox> boxes = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            final EqualBox box = new EqualBox(i);
            boxes.add(box);
            final float x = -1200.0f + i * 200.0f;
            ref.addObject(box, x, 0.0f, 150.0f, 150.0f);
            bvh.addObject(box, x, 0.0f, 150.0f, 150.0f);
        }

        final List<Object> expected = drain(ref.getCheckIterator(0.0f, 0.0f, 6000.0f, 6000.0f));
        final List<Object> actual = drain(bvh.getCheckIterator(0.0f, 0.0f, 6000.0f, 6000.0f));

        assertEquals(1, expected.size(), "原版按 equals 折叠");
        assertEquals(1, actual.size(), "BVH 按 equals 折叠");
        assertTrue(boxes.stream().anyMatch(box -> box == expected.get(0)),
                "原版返回的必须是某个输入引用");
        assertTrue(boxes.stream().anyMatch(box -> box == actual.get(0)),
                "BVH 返回的必须是某个输入引用，而非新建的等值对象");
    }

    @Test
    void hashCollisionAloneDoesNotDropEntries() {
        final VanillaGridReference ref = newRef();
        final CollisionGridBvhImpl bvh = newBvh();
        final List<SameHashBox> boxes = List.of(new SameHashBox(1), new SameHashBox(2), new SameHashBox(3));
        for (final SameHashBox box : boxes) {
            ref.addObject(box, 0.0f, 0.0f, 120.0f, 120.0f);
            bvh.addObject(box, 0.0f, 0.0f, 120.0f, 120.0f);
        }

        final List<Object> actual = drain(bvh.getCheckIterator(0.0f, 0.0f, 600.0f, 600.0f));
        final List<Object> expected = drain(ref.getCheckIterator(0.0f, 0.0f, 600.0f, 600.0f));

        assertEquals(3, expected.size());
        assertEquals(3, actual.size(), "同哈希不同值必须全部保留");
        assertNoDuplicateReferences(actual);
        assertEquals(identitySet(expected), identitySet(actual));
    }

    @Test
    void equalsBecomingTrueAfterRegistrationKeepsFirstOccurrence() {
        final VanillaGridReference ref = newRef();
        final CollisionGridBvhImpl bvh = newBvh();
        final MutableEqualityBox first = new MutableEqualityBox(0);
        final MutableEqualityBox second = new MutableEqualityBox(2);
        ref.addObject(first, -400.0f, 0.0f, 200.0f, 200.0f);
        bvh.addObject(first, -400.0f, 0.0f, 200.0f, 200.0f);
        ref.addObject(second, 400.0f, 0.0f, 200.0f, 200.0f);
        bvh.addObject(second, 400.0f, 0.0f, 200.0f, 200.0f);

        assertFalse(first.equals(second), "登记时两者互不相等");
        assertEquals(2, drain(bvh.getCheckIterator(0.0f, 0.0f, 3000.0f, 3000.0f)).size());

        first.value = 1;
        second.value = 1;
        assertTrue(first.equals(second), "查询前两者已互等");

        final List<Object> expected = drain(ref.getCheckIterator(0.0f, 0.0f, 3000.0f, 3000.0f));
        final List<Object> actual = drain(bvh.getCheckIterator(0.0f, 0.0f, 3000.0f, 3000.0f));

        assertEquals(1, expected.size());
        assertEquals(1, actual.size(), "BVH 必须按查询瞬时的 equals 折叠为一次");
        assertSame(expected.get(0), actual.get(0), "两者都返回瞬时判等的首次出现");
        assertSame(first, actual.get(0), "首次出现是先登记的引用");
    }

    @Test
    void mutableHashCodeBetweenRegistrationsStillDeduplicates() {
        final VanillaGridReference ref = newRef();
        final CollisionGridBvhImpl bvh = newBvh();
        final MutableHashBox box = new MutableHashBox();
        bvh.addObject(box, 0.0f, 0.0f, 200.0f, 200.0f);
        ref.addObject(box, 0.0f, 0.0f, 200.0f, 200.0f);
        box.salt = 99;
        bvh.addObject(box, 0.0f, 0.0f, 200.0f, 200.0f);
        ref.addObject(box, 0.0f, 0.0f, 200.0f, 200.0f);

        final List<Object> expected = drain(ref.getCheckIterator(0.0f, 0.0f, 800.0f, 800.0f));
        final List<Object> actual = drain(bvh.getCheckIterator(0.0f, 0.0f, 800.0f, 800.0f));

        assertEquals(1, expected.size());
        assertEquals(1, actual.size(), "两次登记之间哈希改变，BVH 仍只返回一次");
        assertSame(box, actual.get(0));
    }

    @Test
    void concurrentQueriesKeepPerQuerySequencesIntact() throws Exception {
        assertConcurrentQueriesStable(false);
        assertConcurrentQueriesStable(true);
    }

    private static void assertConcurrentQueriesStable(final boolean buildDuringFirstQuery) throws Exception {
        final float[][] windows = {
                {0.0f, 0.0f, 20000.0f, 20000.0f},
                {-6000.0f, -6000.0f, 4000.0f, 4000.0f},
                {-9000.0f, 0.0f, 12000.0f, 3000.0f},
                {0.0f, -9000.0f, 3000.0f, 12000.0f},
        };

        final CollisionGridBvhImpl bvh = newBvh();
        final List<Object> objects = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            final Object object = new Object();
            objects.add(object);
            final float x = GRID_MIN + (i % 20) * 900.0f;
            final float y = GRID_MIN + (i / 20) * 900.0f;
            bvh.addObject(object, x, y, 600.0f, 600.0f);
        }

        // 期望序列必须来自同一批引用，否则无法比较 identity。
        final CollisionGridBvhImpl reference = newBvh();
        for (int i = 0; i < 400; i++) {
            final float x = GRID_MIN + (i % 20) * 900.0f;
            final float y = GRID_MIN + (i / 20) * 900.0f;
            reference.addObject(objects.get(i), x, y, 600.0f, 600.0f);
        }
        final List<List<Object>> expected = new ArrayList<>();
        for (final float[] window : windows) {
            expected.add(drain(reference.getCheckIterator(window[0], window[1], window[2], window[3])));
        }

        if (!buildDuringFirstQuery) {
            drain(bvh.getCheckIterator(0.0f, 0.0f, 20000.0f, 20000.0f));
        }

        final int threads = 8;
        final int rounds = 200;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                final int offset = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int round = 0; round < rounds; round++) {
                        final int index = (round + offset) % windows.length;
                        final float[] window = windows[index];
                        final List<Object> actual =
                                drain(bvh.getCheckIterator(window[0], window[1], window[2], window[3]));
                        final List<Object> referenceList = expected.get(index);
                        if (actual.size() != referenceList.size()) {
                            throw new AssertionError("窗口 " + index + " 并发查询项数不符：期望 "
                                    + referenceList.size() + "，实际 " + actual.size());
                        }
                        for (int i = 0; i < referenceList.size(); i++) {
                            if (actual.get(i) != referenceList.get(i)) {
                                throw new AssertionError("窗口 " + index + " 第 " + i + " 项引用不符");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (final Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void nestedQueryFromEqualityHookKeepsOuterTraversalIntact() {
        final float[][] outerWindows = {
                {-1000.0f, -1000.0f, 2000.0f, 2000.0f},
                {-5000.0f, -5000.0f, 10000.0f, 10000.0f},
                {0.0f, 0.0f, 20000.0f, 20000.0f},
                {-9000.0f, -9000.0f, 20000.0f, 20000.0f},
        };

        final CollisionGridBvhImpl bvh = newBvh();
        for (int i = 0; i < 200; i++) {
            final float x = GRID_MIN + (i % 20) * 900.0f;
            final float y = GRID_MIN + (i / 20) * 900.0f;
            bvh.addObject(new Object(), x, y, 600.0f, 600.0f);
        }

        final AtomicBoolean armed = new AtomicBoolean(false);
        final NestedQueryBox probe = new NestedQueryBox(bvh, armed);
        bvh.addObject(probe, 0.0f, 0.0f, 300.0f, 300.0f);
        bvh.addObject(probe, 0.0f, 0.0f, 300.0f, 300.0f);
        drain(bvh.getCheckIterator(0.0f, 0.0f, 20000.0f, 20000.0f));

        for (final float[] window : outerWindows) {
            armed.set(false);
            final List<Object> baseline = drain(bvh.getCheckIterator(window[0], window[1], window[2], window[3]));

            armed.set(true);
            final List<Object> withNested = drain(bvh.getCheckIterator(window[0], window[1], window[2], window[3]));
            assertFalse(armed.get(), "外层查询期间必须触发过嵌套查询");

            assertEquals(baseline.size(), withNested.size(),
                    "嵌套查询不得使外层漏项或增多");
            for (int i = 0; i < baseline.size(); i++) {
                assertSame(baseline.get(i), withNested.get(i),
                        "第 " + i + " 项与无嵌套时不一致");
            }
        }
    }

    private static Set<Object> identitySet(final List<Object> values) {
        final Set<Object> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(values);
        return result;
    }

    private static void assertNoDuplicateReferences(final List<Object> values) {
        final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Object value : values) {
            assertTrue(seen.add(value), "同一引用在输出中出现了两次");
        }
    }

    private static List<Object> drain(final Iterator<Object> iterator) {
        final List<Object> result = new ArrayList<>();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }

    private static VanillaGridReference newRef() {
        return new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
    }

    private static CollisionGridBvhImpl newBvh() {
        final int baseX = -((int) Math.floor(GRID_MIN / CELL));
        final int baseY = -((int) Math.floor(GRID_MIN / CELL));
        final int gridWidth = baseX + (int) Math.ceil(GRID_MAX / CELL);
        final int gridHeight = baseY + (int) Math.ceil(GRID_MAX / CELL);
        return new CollisionGridBvhImpl(baseX, baseY, gridWidth, gridHeight, CELL);
    }

    private static final class EqualBox {
        private final int id;

        private EqualBox(final int id) {
            this.id = id;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof EqualBox;
        }

        @Override
        public int hashCode() {
            return 42;
        }

        @Override
        public String toString() {
            return "EqualBox#" + id;
        }
    }

    private static final class SameHashBox {
        private final int id;

        private SameHashBox(final int id) {
            this.id = id;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof SameHashBox box && box.id == id;
        }

        @Override
        public int hashCode() {
            return 7;
        }

        @Override
        public String toString() {
            return "SameHashBox#" + id;
        }
    }

    private static final class MutableEqualityBox {
        private int value;

        private MutableEqualityBox(final int value) {
            this.value = value;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof MutableEqualityBox box && box.value == value;
        }

        @Override
        public int hashCode() {
            return 11;
        }

        @Override
        public String toString() {
            return "MutableEqualityBox#" + value;
        }
    }

    private static final class NestedQueryBox {
        private final CollisionGridBvhImpl grid;
        private final AtomicBoolean armed;

        private NestedQueryBox(final CollisionGridBvhImpl grid, final AtomicBoolean armed) {
            this.grid = grid;
            this.armed = armed;
        }

        @Override
        public int hashCode() {
            if (armed.compareAndSet(true, false)) {
                drain(grid.getCheckIterator(0.0f, 0.0f, 20000.0f, 20000.0f));
            }
            return 5;
        }

        @Override
        public String toString() {
            return "NestedQueryBox";
        }
    }

    private static final class MutableHashBox {
        private final Object identity = new Object();
        private int salt;

        @Override
        public boolean equals(final Object other) {
            return other instanceof MutableHashBox box && box.identity == identity;
        }

        @Override
        public int hashCode() {
            return salt;
        }

        @Override
        public String toString() {
            return "MutableHashBox#" + salt;
        }
    }
}
