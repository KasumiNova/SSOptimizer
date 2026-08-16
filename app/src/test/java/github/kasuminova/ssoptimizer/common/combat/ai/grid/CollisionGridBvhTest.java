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

/**
 * {@link CollisionGridBvhImpl} 对照 {@link VanillaGridReference}（原版算法逐行移植夹具）的语义等价测试。
 * <p>
 * 覆盖：随机实体集 × 随机查询集合逐位相等、快照迭代、remove 幽灵条目、
 * 帧内交替删查（模拟 CollisionEngine 逐实体 remove+query）、BVH 结构校验。
 */
class CollisionGridBvhTest {

    /** 大地图网格参数：含负坐标与跨边界（cellSize=300 与游戏一致）。 */
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

            // 随机查询：越界、零宽高、巨大范围混合。
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
        // 模拟 CollisionEngine 帧内模式：全量 add 后交替 remove/add/query。
        final Random rng = new Random(987654321L);
        final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);

        final List<Object> objects = randomBulkAdd(rng, ref, bvh, 500);
        final List<float[]> dims = new ArrayList<>();
        // 重新记录 dims（randomBulkAdd 内部生成，这里再生成一份独立实体用于后续 add）。
        for (int i = 0; i < 200; i++) {
            dims.add(randomRect(rng, false));
        }

        for (int op = 0; op < 1500; op++) {
            final int kind = rng.nextInt(10);
            if (kind < 5) {
                // 查询（含构建后触发与常规触发）
                final float[] q = randomRect(rng, true);
                assertEquals(drainToSet(ref.getCheckIterator(q[0], q[1], q[2], q[3])),
                        drainToSet(bvh.getCheckIterator(q[0], q[1], q[2], q[3])),
                        "fuzz query op#" + op);
            } else if (kind < 8) {
                // remove：坐标随机抖动（0/±150/±300/±600），制造幽灵条目场景。
                final int idx = rng.nextInt(objects.size());
                final Object obj = objects.get(idx);
                final float[] d = randomRect(rng, false);
                final float jx = (rng.nextInt(5) - 2) * 150.0f;
                final float jy = (rng.nextInt(5) - 2) * 150.0f;
                ref.removeObject(obj, d[0] + jx, d[1] + jy, d[2], d[3]);
                bvh.removeObject(obj, d[0] + jx, d[1] + jy, d[2], d[3]);
            } else {
                // 帧内 add（BVH 构建后进入溢出区）
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
        // 确定性幽灵场景：add 区间 1..3，remove 区间 2..4（X 轴），cell X=1 留幽灵。
        final VanillaGridReference ref = new VanillaGridReference(0, 6000, 0, 6000, CELL);
        final CollisionGridBvhImpl bvh = newBvh(0, 6000, 0, 6000, CELL);
        final Object ghost = new Object();

        ref.addObject(ghost, 750, 750, 600, 600);
        bvh.addObject(ghost, 750, 750, 600, 600);
        ref.removeObject(ghost, 1050, 750, 600, 600);
        bvh.removeObject(ghost, 1050, 750, 600, 600);

        // 幽灵 cell（X=1）可见；已移除 cell（X=2..3）不可见；两侧对比必须一致。
        final float[][] queries = {
                {450, 750, 290, 290},   // 仅覆盖幽灵 cell
                {1050, 750, 290, 290},  // 仅覆盖已移除 cell
                {750, 750, 2000, 2000}, // 覆盖整个原区间
                {750, 750, 0, 0},       // 零宽高
        };
        for (final float[] q : queries) {
            assertEquals(drainToSet(ref.getCheckIterator(q[0], q[1], q[2], q[3])),
                    drainToSet(bvh.getCheckIterator(q[0], q[1], q[2], q[3])));
        }
        // 参考实现确认幽灵确实存在（X=1 查询应命中），证明场景有效。
        assertTrue(drainToSet(ref.getCheckIterator(450, 750, 290, 290)).contains(ghost));
        assertFalse(drainToSet(ref.getCheckIterator(1050, 750, 290, 290)).contains(ghost));
    }

    @Test
    void snapshotIteratorUnaffectedByMutations() {
        final Random rng = new Random(555L);
        final VanillaGridReference ref = new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final CollisionGridBvhImpl bvh = newBvh(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
        final List<Object> objects = randomBulkAdd(rng, ref, bvh, 200);

        // 先触发一次查询（物化 BVH），再取快照迭代器。
        drainToSet(bvh.getCheckIterator(0, 0, 40000, 40000));

        final Iterator<Object> refSnapshot = ref.getCheckIterator(0, 0, 3000, 3000);
        final Iterator<Object> bvhSnapshot = bvh.getCheckIterator(0, 0, 3000, 3000);

        // 快照取出后执行增删。
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

        // 非空结果与空结果的 remove() 都必须抛 UnsupportedOperationException。
        assertThrows(UnsupportedOperationException.class,
                () -> bvh.getCheckIterator(300, 300, 300, 300).remove());
        assertThrows(UnsupportedOperationException.class,
                () -> bvh.getCheckIterator(5000, 5000, 100, 100).remove());
        assertThrows(UnsupportedOperationException.class,
                () -> ref.getCheckIterator(300, 300, 300, 300).remove());

        // 空结果迭代器行为一致。
        assertFalse(bvh.getCheckIterator(5000, 5000, 100, 100).hasNext());
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

    /** 递归校验：内部节点 AABB = 子节点并集；叶子 AABB = 其条目并集；条目恰好被覆盖一次。 */
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

    // ---- 工具方法 ----

    /** 生成随机实体并同时加入参考网格与 BVH；返回对象列表（含 null 与重复 add）。 */
    private static List<Object> randomBulkAdd(final Random rng,
                                              final VanillaGridReference ref,
                                              final CollisionGridBvhImpl bvh,
                                              final int count) {
        final List<Object> objects = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // ~5% null 对象（原版允许）。
            final Object obj = rng.nextInt(20) == 0 ? null : new Object();
            objects.add(obj);
            final float[] d = randomRect(rng, false);
            ref.addObject(obj, d[0], d[1], d[2], d[3]);
            bvh.addObject(obj, d[0], d[1], d[2], d[3]);
            // ~5% 同一对象同位置重复 add（查询端去重）。
            if (rng.nextInt(20) == 0) {
                ref.addObject(obj, d[0], d[1], d[2], d[3]);
                bvh.addObject(obj, d[0], d[1], d[2], d[3]);
            }
        }
        return objects;
    }

    /**
     * 随机矩形 {@code {x, y, w, h}}。
     *
     * @param wideRange true 时坐标可大幅越界（查询用）；false 时集中在网格内及边缘（实体用）
     */
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
            return 40000.0f; // 覆盖全图的巨大范围
        }
        if (pick <= 2) {
            return 0.0f;     // 零宽高
        }
        if (pick == 3) {
            return 5000.0f;  // 超大半径（避导弹扫描量级）
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
