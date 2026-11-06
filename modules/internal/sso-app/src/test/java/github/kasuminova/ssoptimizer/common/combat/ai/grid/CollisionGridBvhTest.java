package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CollisionGridBvhTest {

    private static final float GRID_MIN = -10000.0f;
    private static final float GRID_MAX = 10000.0f;
    private static final float CELL     = 300.0f;

    @Test
    void matchesReference_randomEntitySets() {
        final int[] sizes = {100, 500, 1000};
        for (final int size : sizes) {
            final Random rng = new Random(20260816L + size);
            final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
            final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);

            final List<Object> objects = randomBulkAdd(rng, ref, bvh, size);

            for (int q = 0; q < 300; q++) {
                final float[] query = randomRect(rng, true);
                assertEquals(drainToSet(ref.getCheckIterator(query[0], query[1], query[2], query[3])),
                        drainToSet(bvh.getCheckIterator(query[0], query[1], query[2], query[3])),
                        "size=" + size + " query#" + q);
            }
            assertFalse(objects.isEmpty());
        }
    }

    @Test
    void matchesReference_interleavedFuzz() {
        final Random rng = new Random(987654321L);
        final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);

        final List<Object> objects = randomBulkAdd(rng, ref, bvh, 500);
        final List<float[]> dims = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            dims.add(randomRect(rng, false));
        }

        for (int op = 0; op < 1500; op++) {
            final int kind = rng.nextInt(10);
            if (kind < 5) {
                final float[] q = randomRect(rng, true);
                assertEquals(drainToSet(ref.getCheckIterator(q[0], q[1], q[2], q[3])),
                        drainToSet(bvh.getCheckIterator(q[0], q[1], q[2], q[3])),
                        "fuzz query op#" + op);
            } else if (kind < 8) {
                final int idx = rng.nextInt(objects.size());
                final Object obj = objects.get(idx);
                final float[] d = randomRect(rng, false);
                final float jx = (rng.nextInt(5) - 2) * 150.0f;
                final float jy = (rng.nextInt(5) - 2) * 150.0f;
                ref.removeObject(obj, d[0] + jx, d[1] + jy, d[2], d[3]);
                bvh.removeObject(obj, d[0] + jx, d[1] + jy, d[2], d[3]);
            } else {
                final Object obj = rng.nextInt(20) == 0 ? null : new Object();
                objects.add(obj);
                final float[] d = dims.get(rng.nextInt(dims.size()));
                ref.addObject(obj, d[0], d[1], d[2], d[3]);
                bvh.addObject(obj, d[0], d[1], d[2], d[3]);
            }
        }
    }

    @Test
    void ghostEntriesMatchReference() {
        final VanillaGridReference ref = new VanillaGridReference(0, 6000, 0, 6000, CELL);
        final CollisionGridBvhImpl bvh = newBvh(0, 6000, 0, 6000, CELL);
        final Object ghost = new Object();

        ref.addObject(ghost, 750, 750, 600, 600);
        bvh.addObject(ghost, 750, 750, 600, 600);
        ref.removeObject(ghost, 1050, 750, 600, 600);
        bvh.removeObject(ghost, 1050, 750, 600, 600);

        final float[][] queries = {
                {450, 750, 290, 290},
                {1050, 750, 290, 290},
                {750, 750, 2000, 2000},
                {750, 750, 0, 0},
        };
        for (final float[] q : queries) {
            assertEquals(drainToSet(ref.getCheckIterator(q[0], q[1], q[2], q[3])),
                    drainToSet(bvh.getCheckIterator(q[0], q[1], q[2], q[3])));
        }
        assertTrue(drainToSet(ref.getCheckIterator(450, 750, 290, 290)).contains(ghost));
        assertFalse(drainToSet(ref.getCheckIterator(1050, 750, 290, 290)).contains(ghost));
    }

    @Test
    void snapshotIteratorUnaffectedByMutations() {
        final Random rng = new Random(555L);
        final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final List<Object> objects = randomBulkAdd(rng, ref, bvh, 200);

        drainToSet(bvh.getCheckIterator(0, 0, 40000, 40000));

        final Iterator<Object> refSnapshot = ref.getCheckIterator(0, 0, 3000, 3000);
        final Iterator<Object> bvhSnapshot = bvh.getCheckIterator(0, 0, 3000, 3000);

        for (int i = 0; i < 50; i++) {
            final Object obj = objects.get(rng.nextInt(objects.size()));
            final float[] d = randomRect(rng, false);
            ref.removeObject(obj, d[0], d[1], d[2], d[3]);
            bvh.removeObject(obj, d[0], d[1], d[2], d[3]);
            final Object added = new Object();
            ref.addObject(added, d[0], d[1], d[2], d[3]);
            bvh.addObject(added, d[0], d[1], d[2], d[3]);
        }

        assertEquals(drainToSet(refSnapshot), drainToSet(bvhSnapshot));
    }

    @Test
    void iteratorRemoveThrowsUnsupported() {
        final VanillaGridReference ref = new VanillaGridReference(0, 6000, 0, 6000, CELL);
        final CollisionGridBvhImpl bvh = newBvh(0, 6000, 0, 6000, CELL);
        final Object obj = new Object();
        ref.addObject(obj, 300, 300, 100, 100);
        bvh.addObject(obj, 300, 300, 100, 100);

        assertThrows(UnsupportedOperationException.class,
                () -> bvh.getCheckIterator(300, 300, 300, 300).remove());
        assertThrows(UnsupportedOperationException.class,
                () -> bvh.getCheckIterator(5000, 5000, 100, 100).remove());
        assertThrows(UnsupportedOperationException.class,
                () -> ref.getCheckIterator(300, 300, 300, 300).remove());

        assertFalse(bvh.getCheckIterator(5000, 5000, 100, 100).hasNext());
    }

    @Test
    void largeResultQueriesRemainCompletePastCollectionCapacity() {
        final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final int count = 3000;
        final Random rng = new Random(777L);
        for (int i = 0; i < count; i++) {
            final Object obj = new Object();
            final float[] d = randomRect(rng, false);
            ref.addObject(obj, d[0], d[1], d[2], d[3]);
            bvh.addObject(obj, d[0], d[1], d[2], d[3]);
        }

        final HashSet<Object> expected = drainToSet(ref.getCheckIterator(0, 0, 40000, 40000));
        final HashSet<Object> actual = drainToSet(bvh.getCheckIterator(0, 0, 40000, 40000));
        assertEquals(expected, actual);
        assertTrue(actual.size() > 1000, "全图查询应命中绝大多数实体，实际 " + actual.size());
    }

    @Test
    void bvhInternalNodeBoundsAreUnionOfChildren() {
        final Random rng = new Random(424242L);
        final int n = 200;
        final int[] minX = new int[n];
        final int[] minY = new int[n];
        final int[] maxX = new int[n];
        final int[] maxY = new int[n];
        for (int i = 0; i < n; i++) {
            minX[i] = rng.nextInt(60);
            minY[i] = rng.nextInt(60);
            maxX[i] = minX[i] + rng.nextInt(8);
            maxY[i] = minY[i] + rng.nextInt(8);
        }

        final CollisionGridBvhBuild.Tree tree = CollisionGridBvhBuild.build(minX, minY, maxX, maxY, n);
        final boolean[] covered = new boolean[n];
        validateNode(tree, 0, minX, minY, maxX, maxY, covered);
        for (int i = 0; i < n; i++) {
            assertTrue(covered[i], "条目 " + i + " 未被任何叶子覆盖");
        }
    }

    private static void validateNode(final CollisionGridBvhBuild.Tree tree,
                                     final int node,
                                     final int[] minX, final int[] minY,
                                     final int[] maxX, final int[] maxY,
                                     final boolean[] covered) {
        if (tree.nLeft[node] < 0) {
            int lo = Integer.MAX_VALUE;
            int to = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            int bo = Integer.MIN_VALUE;
            final int start = tree.nEntryStart[node];
            final int end = start + tree.nEntryCount[node];
            assertTrue(end > start, "叶子节点不能为空");
            assertTrue(end - start <= CollisionGridBvhBuild.LEAF_CAPACITY, "叶子超出容量");
            for (int i = start; i < end; i++) {
                final int e = tree.entryOrder[i];
                assertFalse(covered[e], "条目 " + e + " 被多个叶子覆盖");
                covered[e] = true;
                lo = Math.min(lo, minX[e]);
                to = Math.min(to, minY[e]);
                hi = Math.max(hi, maxX[e]);
                bo = Math.max(bo, maxY[e]);
            }
            assertEquals(lo, tree.nMinX[node]);
            assertEquals(to, tree.nMinY[node]);
            assertEquals(hi, tree.nMaxX[node]);
            assertEquals(bo, tree.nMaxY[node]);
            return;
        }

        final int left = tree.nLeft[node];
        final int right = tree.nRight[node];
        validateNode(tree, left, minX, minY, maxX, maxY, covered);
        validateNode(tree, right, minX, minY, maxX, maxY, covered);
        assertEquals(Math.min(tree.nMinX[left], tree.nMinX[right]), tree.nMinX[node]);
        assertEquals(Math.min(tree.nMinY[left], tree.nMinY[right]), tree.nMinY[node]);
        assertEquals(Math.max(tree.nMaxX[left], tree.nMaxX[right]), tree.nMaxX[node]);
        assertEquals(Math.max(tree.nMaxY[left], tree.nMaxY[right]), tree.nMaxY[node]);
    }

    private static List<Object> randomBulkAdd(final Random rng,
                                              final VanillaGridReference ref,
                                              final CollisionGridBvhImpl bvh,
                                              final int count) {
        final List<Object> objects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            final Object obj = rng.nextInt(20) == 0 ? null : new Object();
            objects.add(obj);
            final float[] d = randomRect(rng, false);
            ref.addObject(obj, d[0], d[1], d[2], d[3]);
            bvh.addObject(obj, d[0], d[1], d[2], d[3]);
            if (rng.nextInt(20) == 0) {
                ref.addObject(obj, d[0], d[1], d[2], d[3]);
                bvh.addObject(obj, d[0], d[1], d[2], d[3]);
            }
        }
        return objects;
    }

    private static float[] randomRect(final Random rng, final boolean wideRange) {
        final float span = GRID_MAX - GRID_MIN;
        final float margin = wideRange ? span : 2000.0f;
        final float x = GRID_MIN - margin + rng.nextFloat() * (span + margin * 2);
        final float y = GRID_MIN - margin + rng.nextFloat() * (span + margin * 2);
        final float w = randomSize(rng, wideRange);
        final float h = randomSize(rng, wideRange);
        return new float[]{x, y, w, h};
    }

    private static float randomSize(final Random rng, final boolean allowHuge) {
        final int pick = rng.nextInt(20);
        if (allowHuge && pick == 0) {
            return 40000.0f;
        }
        if (pick <= 2) {
            return 0.0f;
        }
        if (pick == 3) {
            return 5000.0f;
        }
        return 20.0f + rng.nextFloat() * 800.0f;
    }

    private static CollisionGridBvhImpl newBvh(final float minX, final float maxX,
                                               final float minY, final float maxY,
                                               final float cellSize) {
        final int baseX = -((int) Math.floor(minX / cellSize));
        final int gridWidth = baseX + (int) Math.ceil(maxX / cellSize);
        final int baseY = -((int) Math.floor(minY / cellSize));
        final int gridHeight = baseY + (int) Math.ceil(maxY / cellSize);
        return new CollisionGridBvhImpl(baseX, baseY, gridWidth, gridHeight, cellSize);
    }

    private static HashSet<Object> drainToSet(final Iterator<Object> iterator) {
        final HashSet<Object> result = new HashSet<>();
        while (iterator.hasNext()) {
            result.add(iterator.next());
        }
        return result;
    }
}
