package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * 懒构建的扁平 BVH，构建后的新增条目进入溢出区并在查询时补扫。
 * 移除操作按插入顺序消耗条目的可用区域，保留原版幽灵条目行为。
 */
public final class CollisionGridBvhImpl implements CollisionGridBvh {
    private static final Logger LOGGER = Logger.getLogger(CollisionGridBvhImpl.class);

    private static final int QUERY_COLLECTION_INITIAL_CAPACITY = 64;

    private static final boolean ENABLED =
            Boolean.parseBoolean(System.getProperty("ssoptimizer.collisionGridBvh", "true"));

    static {
        LOGGER.info("[SSOptimizer] CollisionGrid BVH 查询优化: " + (ENABLED ? "启用" : "关闭（回退 fastutil 网格收集器）"));
    }

    private final int   baseX;
    private final int   baseY;
    private final int   gridWidth;
    private final int   gridHeight;
    private final float cellSize;

    private final ArrayList<Entry> entries = new ArrayList<>();

    private final HashMap<Object, List<Entry>> entriesByObject = new HashMap<>();

    private CollisionGridBvhBuild.Tree tree;
    private Entry[]                    builtEntries;
    private int                        builtEntryCount;
    private boolean                    built;

    private int[] traversalStack = new int[64];

    public CollisionGridBvhImpl(final int baseX,
                                final int baseY,
                                final int gridWidth,
                                final int gridHeight,
                                final float cellSize) {
        this.baseX = baseX;
        this.baseY = baseY;
        this.gridWidth = gridWidth;
        this.gridHeight = gridHeight;
        this.cellSize = cellSize;
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    @Override
    public void addObject(final Object object,
                          final float centerX,
                          final float centerY,
                          final float width,
                          final float height) {
        final int[] range = CollisionGridBvhBuild.clampedCellRange(
                baseX, baseY, gridWidth, gridHeight, cellSize, centerX, centerY, width, height);
        if (range == null) {
            return;
        }
        final Entry entry = new Entry(object, range);
        entries.add(entry);
        entriesByObject.computeIfAbsent(object, key -> new ArrayList<>(1)).add(entry);
    }

    @Override
    public void removeObject(final Object object,
                             final float centerX,
                             final float centerY,
                             final float width,
                             final float height) {
        final int[] range = CollisionGridBvhBuild.clampedCellRange(
                baseX, baseY, gridWidth, gridHeight, cellSize, centerX, centerY, width, height);
        if (range == null) {
            return;
        }

        final List<Entry> candidates = entriesByObject.get(object);
        if (candidates == null) {
            return;
        }
        List<int[]> remaining = new ArrayList<>(1);
        remaining.add(range);
        final Iterator<Entry> it = candidates.iterator();
        while (it.hasNext()) {
            final Entry entry = it.next();
            if (entry.avail.isEmpty()) {
                it.remove();
                continue;
            }
            final List<int[]> hit = CollisionGridBvhBuild.intersectRects(entry.avail, remaining);
            if (hit.isEmpty()) {
                continue;
            }
            entry.avail = CollisionGridBvhBuild.subtractRects(entry.avail, hit);
            if (entry.avail.isEmpty()) {
                it.remove();
            }
            remaining = CollisionGridBvhBuild.subtractRects(remaining, hit);
            if (remaining.isEmpty()) {
                break;
            }
        }
        if (candidates.isEmpty()) {
            entriesByObject.remove(object);
        }
    }

    @Override
    public Iterator<Object> getCheckIterator(final float centerX,
                                             final float centerY,
                                             final float width,
                                             final float height) {
        final int[] query = CollisionGridBvhBuild.clampedCellRange(
                baseX, baseY, gridWidth, gridHeight, cellSize, centerX, centerY, width, height);
        if (query == null || entries.isEmpty()) {
            return new SnapshotIterator(null, 0);
        }

        ensureBuilt();

        final ObjectOpenHashSet<Object> seen = new ObjectOpenHashSet<>(QUERY_COLLECTION_INITIAL_CAPACITY);
        final ObjectArrayList<Object> ordered = new ObjectArrayList<>(QUERY_COLLECTION_INITIAL_CAPACITY);

        if (tree != null) {
            traverse(query, seen, ordered);
        }
        for (int i = builtEntryCount, size = entries.size(); i < size; i++) {
            collectIfVisible(entries.get(i), query, seen, ordered);
        }

        return new SnapshotIterator(ordered.elements(), ordered.size());
    }

    private void ensureBuilt() {
        if (built) {
            return;
        }
        built = true;
        builtEntryCount = entries.size();

        final ArrayList<Entry> alive = new ArrayList<>(builtEntryCount);
        for (final Entry entry : entries) {
            if (!entry.avail.isEmpty()) {
                alive.add(entry);
            }
        }
        if (alive.isEmpty()) {
            tree = null;
            builtEntries = new Entry[0];
            return;
        }

        final int count = alive.size();
        final int[] minX = new int[count];
        final int[] minY = new int[count];
        final int[] maxX = new int[count];
        final int[] maxY = new int[count];
        builtEntries = alive.toArray(new Entry[0]);
        for (int i = 0; i < count; i++) {
            // 叶子包围盒取可用矩形的并集，保证剪枝保守。
            int lo = Integer.MAX_VALUE;
            int to = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            int bo = Integer.MIN_VALUE;
            for (final int[] rect : builtEntries[i].avail) {
                lo = Math.min(lo, rect[0]);
                to = Math.min(to, rect[1]);
                hi = Math.max(hi, rect[2]);
                bo = Math.max(bo, rect[3]);
            }
            minX[i] = lo;
            minY[i] = to;
            maxX[i] = hi;
            maxY[i] = bo;
        }
        tree = CollisionGridBvhBuild.build(minX, minY, maxX, maxY, count);
    }

    private void traverse(final int[] query,
                          final ObjectOpenHashSet<Object> seen,
                          final ObjectArrayList<Object> ordered) {
        final CollisionGridBvhBuild.Tree t = tree;
        int[] stack = traversalStack;
        int sp = 0;
        stack[sp++] = 0;

        while (sp > 0) {
            final int node = stack[--sp];
            if (query[0] > t.nMaxX[node] || t.nMinX[node] > query[2]
                    || query[1] > t.nMaxY[node] || t.nMinY[node] > query[3]) {
                continue;
            }

            if (t.nLeft[node] < 0) {
                final int start = t.nEntryStart[node];
                final int end = start + t.nEntryCount[node];
                for (int i = start; i < end; i++) {
                    collectIfVisible(builtEntries[t.entryOrder[i]], query, seen, ordered);
                }
                continue;
            }

            if (sp + 2 > stack.length) {
                final int[] grown = new int[stack.length * 2];
                System.arraycopy(stack, 0, grown, 0, sp);
                stack = grown;
                traversalStack = grown;
            }
            stack[sp++] = t.nLeft[node];
            stack[sp++] = t.nRight[node];
        }
    }

    private static void collectIfVisible(final Entry entry,
                                         final int[] query,
                                         final ObjectOpenHashSet<Object> seen,
                                         final ObjectArrayList<Object> ordered) {
        for (final int[] rect : entry.avail) {
            if (CollisionGridBvhBuild.rectsIntersect(rect, query)) {
                if (seen.add(entry.object)) {
                    ordered.add(entry.object);
                }
                return;
            }
        }
    }

    private static final class Entry {
        final Object       object;
        List<int[]> avail;

        Entry(final Object object, final int[] addRange) {
            this.object = object;
            this.avail = new ArrayList<>(1);
            this.avail.add(addRange);
        }
    }

    private static final class SnapshotIterator implements Iterator<Object> {
        private final Object[] data;
        private final int      size;
        private       int      cursor;

        SnapshotIterator(final Object[] data, final int size) {
            this.data = data;
            this.size = size;
        }

        @Override
        public boolean hasNext() {
            return cursor < size;
        }

        @Override
        public Object next() {
            return data[cursor++];
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
