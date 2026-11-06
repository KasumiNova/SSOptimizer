package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import it.unimi.dsi.fastutil.ints.IntArrayList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class CollisionGridBvhBuild {
    static final int LEAF_CAPACITY = 4;

    private CollisionGridBvhBuild() {
    }

    /**
     * 将世界坐标矩形转换为网格内的闭区间，坐标向零截断并逐边界限制范围。
     * 空区间或完全越界时返回 {@code null}。
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

    public static boolean rectsIntersect(final int[] a, final int[] b) {
        return a[0] <= b[2] && b[0] <= a[2] && a[1] <= b[3] && b[1] <= a[3];
    }

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

    private static void subtractOne(final int[] rect, final int[] cutter, final List<int[]> out) {
        if (!rectsIntersect(rect, cutter)) {
            out.add(rect);
            return;
        }
        final int ix0 = Math.max(rect[0], cutter[0]);
        final int iy0 = Math.max(rect[1], cutter[1]);
        final int ix1 = Math.min(rect[2], cutter[2]);
        final int iy1 = Math.min(rect[3], cutter[3]);
        if (rect[0] < ix0) {
            out.add(new int[]{rect[0], rect[1], ix0 - 1, rect[3]});
        }
        if (ix1 < rect[2]) {
            out.add(new int[]{ix1 + 1, rect[1], rect[2], rect[3]});
        }
        if (rect[1] < iy0) {
            out.add(new int[]{ix0, rect[1], ix1, iy0 - 1});
        }
        if (iy1 < rect[3]) {
            out.add(new int[]{ix0, iy1 + 1, ix1, rect[3]});
        }
    }

    /** 使用各数组前 {@code count} 项构建扁平 BVH；无条目时返回 {@code null}。 */
    public static Tree build(final int[] entryMinX,
                             final int[] entryMinY,
                             final int[] entryMaxX,
                             final int[] entryMaxY,
                             final int count) {
        if (count <= 0) {
            return null;
        }

        // 高 32 位存 Morton 码，低 32 位存条目索引。
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

    /** 仅支持网格范围限制后的 16 位坐标。 */
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
     * SoA 扁平树，根节点下标为 0。
     * 内部节点通过左右下标寻址；叶子的左下标为 -1，条目范围由起始下标和数量表示。
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

    private static final class Builder {
        private final int[] entryMinX;
        private final int[] entryMinY;
        private final int[] entryMaxX;
        private final int[] entryMaxY;
        private final int[] order;

        private final IntArrayList nMinX;
        private final IntArrayList nMinY;
        private final IntArrayList nMaxX;
        private final IntArrayList nMaxY;
        private final IntArrayList nLeft;
        private final IntArrayList nRight;
        private final IntArrayList nEntryStart;
        private final IntArrayList nEntryCount;

        Builder(final int[] entryMinX, final int[] entryMinY,
                final int[] entryMaxX, final int[] entryMaxY,
                final int[] order) {
            this.entryMinX = entryMinX;
            this.entryMinY = entryMinY;
            this.entryMaxX = entryMaxX;
            this.entryMaxY = entryMaxY;
            this.order = order;
            final int nodeCapacity = order.length * 2;
            nMinX = new IntArrayList(nodeCapacity);
            nMinY = new IntArrayList(nodeCapacity);
            nMaxX = new IntArrayList(nodeCapacity);
            nMaxY = new IntArrayList(nodeCapacity);
            nLeft = new IntArrayList(nodeCapacity);
            nRight = new IntArrayList(nodeCapacity);
            nEntryStart = new IntArrayList(nodeCapacity);
            nEntryCount = new IntArrayList(nodeCapacity);
        }

        int buildNode(final int lo, final int hi) {
            final int index = nMinX.size();
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
