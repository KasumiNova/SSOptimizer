package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 碰撞网格扁平 BVH 的纯静态构建器与 cell 矩形几何运算。
 * <p>
 * 构建流程（LBVH 简化版，条目数通常在千级，取简单实现）：
 * <ol>
 *     <li>叶子条目为 cell 整数空间 AABB（{@code [minCellX, minCellY, maxCellX, maxCellY]}，闭区间）；</li>
 *     <li>按叶子中心的 Morton 码（Z 序）排序；</li>
 *     <li>自顶向下中点切分生成二叉树，再扁平化为 int 数组 SoA 节点布局；</li>
 *     <li>遍历使用显式 int 栈，无递归、迭代期无分配。</li>
 * </ol>
 * 本类不持有任何实例状态，可直接单测。
 */
public final class CollisionGridBvhBuild {
    /** 叶子节点最大条目数。 */
    static final int LEAF_CAPACITY = 4;

    private CollisionGridBvhBuild() {
    }

    /**
     * 计算世界坐标矩形对应的 cell 区间并 clamp 到网格内（闭区间）。
     * <p>
     * 逐位复刻原版 {@code CollisionGridQuery.addObject/removeObject/getCheckIterator} 的区间计算：
     * {@code (int)(base + (center ± size/2) / cellSize)} —— int 与 float 混合运算后向零截断；
     * clamp 复刻原版 addToCell/getCheckIterator 的逐边界检查（min 只向上收、max 只向下收），
     * clamp 后 min &gt; max 视为空区间。
     *
     * @return {@code int[]{minCellX, minCellY, maxCellX, maxCellY}}；空区间（含完全越界）返回 null
     */
    public static int[] clampedCellRange(final int baseX,
                                         final int baseY,
                                         final int gridWidth,
                                         final int gridHeight,
                                         final float cellSize,
                                         final float centerX,
                                         final float centerY,
                                         final float width,
                                         final float height) {
        if (gridWidth <= 0 || gridHeight <= 0) {
            return null;
        }
        int minX = (int) (baseX + (centerX - width / 2.0f) / cellSize);
        int minY = (int) (baseY + (centerY - height / 2.0f) / cellSize);
        int maxX = (int) (baseX + (centerX + width / 2.0f) / cellSize);
        int maxY = (int) (baseY + (centerY + height / 2.0f) / cellSize);
        if (minX < 0) {
            minX = 0;
        }
        if (minY < 0) {
            minY = 0;
        }
        if (maxX >= gridWidth) {
            maxX = gridWidth - 1;
        }
        if (maxY >= gridHeight) {
            maxY = gridHeight - 1;
        }
        if (minX > maxX || minY > maxY) {
            return null;
        }
        return new int[]{minX, minY, maxX, maxY};
    }

    /** 两个 cell 矩形（int[4] 闭区间）是否相交。 */
    public static boolean rectsIntersect(final int[] a, final int[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

    /** 矩形列表与矩形列表的交集（所有两两相交部分），无交集返回空列表。 */
    public static List<int[]> intersectRects(final List<int[]> a, final List<int[]> b) {
        final List<int[]> result = new ArrayList<>();
        for (final int[] ra : a) {
            for (final int[] rb : b) {
                if (!rectsIntersect(ra, rb)) {
                    continue;
                }
                result.add(new int[]{
                        Math.max(ra[0], rb[0]),
                        Math.max(ra[1], rb[1]),
                        Math.min(ra[2], rb[2]),
                        Math.min(ra[3], rb[3])});
            }
        }
        return result;
    }

    /** 矩形列表减法：{@code a - b}，结果为不重叠矩形列表。 */
    public static List<int[]> subtractRects(final List<int[]> a, final List<int[]> b) {
        List<int[]> remaining = new ArrayList<>(a.size());
        for (final int[] rect : a) {
            remaining.add(new int[]{rect[0], rect[1], rect[2], rect[3]});
        }
        for (final int[] cutter : b) {
            final List<int[]> next = new ArrayList<>(remaining.size());
            for (final int[] rect : remaining) {
                subtractOne(rect, cutter, next);
            }
            remaining = next;
            if (remaining.isEmpty()) {
                break;
            }
        }
        return remaining;
    }

    /** 单矩形减单矩形，把剩余部分（0~4 个矩形）追加到 out。 */
    private static void subtractOne(final int[] rect, final int[] cutter, final List<int[]> out) {
        if (!rectsIntersect(rect, cutter)) {
            out.add(rect);
            return;
        }
        final int ix0 = Math.max(rect[0], cutter[0]);
        final int iy0 = Math.max(rect[1], cutter[1]);
        final int ix1 = Math.min(rect[2], cutter[2]);
        final int iy1 = Math.min(rect[3], cutter[3]);
        // 左条带（贯通全高）
        if (rect[0] < ix0) {
            out.add(new int[]{rect[0], rect[1], ix0 - 1, rect[3]});
        }
        // 右条带（贯通全高）
        if (ix1 < rect[2]) {
            out.add(new int[]{ix1 + 1, rect[1], rect[2], rect[3]});
        }
        // 下条带（仅限交集横向范围）
        if (rect[1] < iy0) {
            out.add(new int[]{ix0, rect[1], ix1, iy0 - 1});
        }
        // 上条带（仅限交集横向范围）
        if (iy1 < rect[3]) {
            out.add(new int[]{ix0, iy1 + 1, ix1, rect[3]});
        }
    }

    /**
     * 构建扁平 BVH。
     *
     * @param entryMinX 条目 cell 区间最小 X（按条目索引）
     * @param entryMinY 条目 cell 区间最小 Y
     * @param entryMaxX 条目 cell 区间最大 X
     * @param entryMaxY 条目 cell 区间最大 Y
     * @param count     有效条目数（取各数组前 count 项）
     * @return 扁平树；count 为 0 时返回 null
     */
    public static Tree build(final int[] entryMinX,
                             final int[] entryMinY,
                             final int[] entryMaxX,
                             final int[] entryMaxY,
                             final int count) {
        if (count <= 0) {
            return null;
        }

        // Morton 码排序：code 为高 32 位、条目索引为低 32 位，一次 long 排序完成。
        final long[] sortKeys = new long[count];
        for (int i = 0; i < count; i++) {
            final int centerX = (entryMinX[i] + entryMaxX[i]) / 2;
            final int centerY = (entryMinY[i] + entryMaxY[i]) / 2;
            sortKeys[i] = ((long) morton2D(centerX, centerY) << 32) | (i & 0xFFFFFFFFL);
        }
        Arrays.sort(sortKeys);

        final int[] order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = (int) sortKeys[i];
        }

        final Builder builder = new Builder(entryMinX, entryMinY, entryMaxX, entryMaxY, order);
        builder.buildNode(0, count);
        return builder.toTree();
    }

    /** 16 位 x/y 交错的 Morton 码（cell 坐标 clamp 后远小于 65536）。 */
    static int morton2D(final int x, final int y) {
        return (part1By1(x) | (part1By1(y) << 1));
    }

    private static int part1By1(int v) {
        v &= 0x0000FFFF;
        v = (v | (v << 8)) & 0x00FF00FF;
        v = (v | (v << 4)) & 0x0F0F0F0F;
        v = (v | (v << 2)) & 0x33333333;
        v = (v | (v << 1)) & 0x55555555;
        return v;
    }

    /**
     * 扁平 BVH 树（SoA int 数组布局）。
     * <p>
     * 节点 {@code i} 的 AABB 为 {@code [nMinX[i], nMinY[i], nMaxX[i], nMaxY[i]]}（cell 闭区间）。
     * 内部节点：{@code nLeft[i]}/{@code nRight[i]} 为子节点下标；
     * 叶子节点：{@code nLeft[i] == -1}，条目为 {@code entryOrder[nEntryStart[i] .. nEntryStart[i]+nEntryCount[i])}。
     * 根节点下标恒为 0。
     */
    public static final class Tree {
        final int[] nMinX;
        final int[] nMinY;
        final int[] nMaxX;
        final int[] nMaxY;
        final int[] nLeft;
        final int[] nRight;
        final int[] nEntryStart;
        final int[] nEntryCount;
        final int[] entryOrder;
        final int nodeCount;

        Tree(final int nodeCount,
             final int[] nMinX, final int[] nMinY, final int[] nMaxX, final int[] nMaxY,
             final int[] nLeft, final int[] nRight,
             final int[] nEntryStart, final int[] nEntryCount,
             final int[] entryOrder) {
            this.nodeCount = nodeCount;
            this.nMinX = nMinX;
            this.nMinY = nMinY;
            this.nMaxX = nMaxX;
            this.nMaxY = nMaxY;
            this.nLeft = nLeft;
            this.nRight = nRight;
            this.nEntryStart = nEntryStart;
            this.nEntryCount = nEntryCount;
            this.entryOrder = entryOrder;
        }

        public int nodeCount() {
            return nodeCount;
        }
    }

    /** 中点切分递归构建器（仅构建期使用，输出扁平数组）。 */
    private static final class Builder {
        private final int[] entryMinX;
        private final int[] entryMinY;
        private final int[] entryMaxX;
        private final int[] entryMaxY;
        private final int[] order;

        private final IntArrayList nMinX = new IntArrayList();
        private final IntArrayList nMinY = new IntArrayList();
        private final IntArrayList nMaxX = new IntArrayList();
        private final IntArrayList nMaxY = new IntArrayList();
        private final IntArrayList nLeft = new IntArrayList();
        private final IntArrayList nRight = new IntArrayList();
        private final IntArrayList nEntryStart = new IntArrayList();
        private final IntArrayList nEntryCount = new IntArrayList();

        Builder(final int[] entryMinX, final int[] entryMinY,
                final int[] entryMaxX, final int[] entryMaxY,
                final int[] order) {
            this.entryMinX = entryMinX;
            this.entryMinY = entryMinY;
            this.entryMaxX = entryMaxX;
            this.entryMaxY = entryMaxY;
            this.order = order;
        }

        /** 构建覆盖 {@code order[lo..hi)} 的子树，返回节点下标（前序分配，根为 0）。 */
        int buildNode(final int lo, final int hi) {
            final int index = nMinX.size();
            // 先占位，子节点分配后再回填。
            nMinX.add(0);
            nMinY.add(0);
            nMaxX.add(0);
            nMaxY.add(0);
            nLeft.add(-1);
            nRight.add(-1);
            nEntryStart.add(0);
            nEntryCount.add(0);

            if (hi - lo <= LEAF_CAPACITY) {
                nEntryStart.set(index, lo);
                nEntryCount.set(index, hi - lo);
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int maxY = Integer.MIN_VALUE;
                for (int i = lo; i < hi; i++) {
                    final int e = order[i];
                    minX = Math.min(minX, entryMinX[e]);
                    minY = Math.min(minY, entryMinY[e]);
                    maxX = Math.max(maxX, entryMaxX[e]);
                    maxY = Math.max(maxY, entryMaxY[e]);
                }
                nMinX.set(index, minX);
                nMinY.set(index, minY);
                nMaxX.set(index, maxX);
                nMaxY.set(index, maxY);
                return index;
            }

            final int mid = (lo + hi) >>> 1;
            final int left = buildNode(lo, mid);
            final int right = buildNode(mid, hi);
            nLeft.set(index, left);
            nRight.set(index, right);
            nMinX.set(index, Math.min(nMinX.getInt(left), nMinX.getInt(right)));
            nMinY.set(index, Math.min(nMinY.getInt(left), nMinY.getInt(right)));
            nMaxX.set(index, Math.max(nMaxX.getInt(left), nMaxX.getInt(right)));
            nMaxY.set(index, Math.max(nMaxY.getInt(left), nMaxY.getInt(right)));
            return index;
        }

        Tree toTree() {
            return new Tree(nMinX.size(),
                    nMinX.toIntArray(), nMinY.toIntArray(), nMaxX.toIntArray(), nMaxY.toIntArray(),
                    nLeft.toIntArray(), nRight.toIntArray(),
                    nEntryStart.toIntArray(), nEntryCount.toIntArray(),
                    order);
        }
    }
}
