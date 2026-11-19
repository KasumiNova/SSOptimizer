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
    private static final ThreadLocal<int[]> TRAVERSAL_STACK = new ThreadLocal<>();
    private static final ClassValue<Boolean> USES_OBJECT_EQUALITY_AND_HASH = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("equals", Object.class).getDeclaringClass() == Object.class
                        && type.getMethod("hashCode").getDeclaringClass() == Object.class;
            } catch (LinkageError | SecurityException e) {
                LOGGER.warn("无法检查 " + type.getName() + " 的 equals/hashCode 声明，BVH 查询将使用完整去重", e);
                return false;
            } catch (NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }
    };

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

    private final HashMap<Object, ArrayList<Entry>> entriesByObject = new HashMap<>();

    private CollisionGridBvhBuild.Tree tree;
    private Entry[]                    builtEntries;
    private int                        builtEntryCount;
    private volatile boolean           built;

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
        final ArrayList<Entry> registrations = entriesByObject.computeIfAbsent(object, key -> new ArrayList<>(1));
        final Entry entry = new Entry(object, range, registrations);
        entries.add(entry);
        registrations.add(entry);
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
                entry.identityRegistrations = null;
                continue;
            }
            final List<int[]> hit = CollisionGridBvhBuild.intersectRects(entry.avail, remaining);
            if (hit.isEmpty()) {
                continue;
            }
            entry.avail = CollisionGridBvhBuild.subtractRects(entry.avail, hit);
            if (entry.avail.isEmpty()) {
                it.remove();
                entry.identityRegistrations = null;
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

        if (!built) buildIfNeeded();

        final ObjectArrayList<Object> ordered = new ObjectArrayList<>(QUERY_COLLECTION_INITIAL_CAPACITY);
        ObjectOpenHashSet<Object> seen = tree == null ? null : traverse(query, ordered);
        for (int i = builtEntryCount, size = entries.size(); i < size; i++) {
            seen = collectIfVisible(entries.get(i), query, seen, ordered);
        }

        return new SnapshotIterator(ordered.elements(), ordered.size());
    }

    private synchronized void buildIfNeeded() {
        if (!built) {
            buildTree();
            built = true;
        }
    }

    private void buildTree() {
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

    private ObjectOpenHashSet<Object> traverse(final int[] query, final ObjectArrayList<Object> ordered) {
        ObjectOpenHashSet<Object> seen = null;
        final CollisionGridBvhBuild.Tree t = tree;
        int[] stack = TRAVERSAL_STACK.get();
        if (stack == null) {
            stack = new int[64];
        } else {
            // 实体的 hashCode 或 equals 可能触发嵌套查询，活动栈必须独占。
            TRAVERSAL_STACK.set(null);
        }
        try {
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
                        seen = collectIfVisible(builtEntries[t.entryOrder[i]], query, seen, ordered);
                    }
                    continue;
                }

                if (sp + 2 > stack.length) {
                    stack = java.util.Arrays.copyOf(stack, stack.length * 2);
                }
                stack[sp++] = t.nLeft[node];
                stack[sp++] = t.nRight[node];
            }
            return seen;
        } finally {
            TRAVERSAL_STACK.set(stack);
        }
    }

    private static ObjectOpenHashSet<Object> collectIfVisible(final Entry entry, final int[] query,
                                                              ObjectOpenHashSet<Object> seen,
                                                              final ObjectArrayList<Object> ordered) {
        for (final int[] rect : entry.avail) {
            if (!CollisionGridBvhBuild.rectsIntersect(rect, query)) {
                continue;
            }
            if (entry.identityRegistrations != null && entry.identityRegistrations.size() == 1) {
                ordered.add(entry.object);
            } else {
                if (seen == null) seen = new ObjectOpenHashSet<>();
                if (seen.add(entry.object)) ordered.add(entry.object);
            }
            break;
        }
        return seen;
    }

    private static final class Entry {
        final Object object;
        ArrayList<Entry> identityRegistrations;
        List<int[]> avail;

        Entry(final Object object, final int[] addRange, final ArrayList<Entry> registrations) {
            this.object = object;
            this.identityRegistrations = object == null || USES_OBJECT_EQUALITY_AND_HASH.get(object.getClass())
                    ? registrations : null;
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
