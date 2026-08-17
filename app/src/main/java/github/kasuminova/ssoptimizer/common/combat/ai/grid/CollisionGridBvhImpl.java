package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

/**
 * {@link CollisionGridBvh} 的实现：懒构建扁平 BVH + 帧内 tombstone/溢出增量。
 * <p>
 * 生命周期贴合游戏用法（每帧新建网格实例，见 {@code CombatEngine.recreateAiGridsIfNeeded} 与
 * {@code CollisionEngine.processCollisions}）：
 * <ul>
 *     <li>add 阶段：仅向条目缓冲追加（cell 区间已 clamp 的非空矩形），不做任何树操作；</li>
 *     <li>首次查询：若树未构建，对当前全部活跃条目一次性物化扁平 BVH
 *         （{@link CollisionGridBvhBuild}）；整帧无查询的网格不产生构建成本；</li>
 *     <li>构建之后的帧内 add：进入溢出区（条目缓冲中 {@code builtEntryCount} 之后的部分），
 *         查询时线性补扫；</li>
 *     <li>帧内 remove：逐位复刻原版 remove 语义 —— 按移除矩形逐 cell 消耗条目的可用矩形列表；
 *         移除区间未覆盖的 cell 保留幽灵条目（原版行为，刻意保留）。
 *         条目按插入顺序消耗，等价于原版 cell 桶内 {@code List.remove} 移除首个匹配项。</li>
 * </ul>
 * 匹配语义：原版 {@code List.remove(Object)} 使用 equals，故此处同样按 equals 匹配
 * （游戏实体未覆写 equals 时即 identity）。
 * <p>
 * 已知偏差：查询结果迭代顺序为 Morton 序（原版为 cell 行优先扫描序），游戏内调用方均自行做
 * 精确碰撞过滤，不依赖顺序。
 */
public final class CollisionGridBvhImpl implements CollisionGridBvh {
    private static final Logger LOGGER = Logger.getLogger(CollisionGridBvhImpl.class);

    /**
     * 功能开关：{@code -Dssoptimizer.collisionGridBvh=false} 时回退到现有 fastutil 收集器路径。
     * 静态初始化时读取一次。
     */
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

    /** 全部登记条目（含已被 remove 消耗殆尽的），插入顺序即原版 addObject 调用顺序。 */
    private final ArrayList<Entry> entries = new ArrayList<>();

    /**
     * 对象 → 该对象的全部登记条目（插入顺序）。
     * removeObject 的匹配条件仅为 equals（未匹配条目在扫描中不产生任何副作用），
     * 因此按对象索引直接定位候选条目与全表扫描严格等价，把单次移除从
     * O（全表条目数） 降到 O（该对象的登记数）。条目被消耗殆尽时从索引摘除。
     */
    private final HashMap<Object, List<Entry>> entriesByObject = new HashMap<>();

    /** 懒构建的扁平树；构建后若无活跃条目则为 null（以 {@link #built} 区分未构建）。 */
    private CollisionGridBvhBuild.Tree tree;
    /** 树叶子条目索引指向的条目数组（构建时活跃条目的快照）。 */
    private Entry[]                    builtEntries;
    /** 构建时 {@link #entries} 的大小；其后的条目为溢出区，查询时线性补扫。 */
    private int                        builtEntryCount;
    /** 是否已完成首次物化构建。 */
    private boolean                    built;

    /** 遍历用显式栈（复用，按需扩容）。 */
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
            // 完全落在网格外：原版 addToCell 逐 cell 越界跳过，等价于不登记。
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

        // remaining 为尚未消耗的移除区域；仅扫描该对象的登记条目（插入顺序），
        // 与原版「每个 cell 移除桶内首个匹配项」逐 cell 等价——未匹配条目在
        // 原版全表扫描中不产生副作用，按对象索引定位不改变结果。
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

        final ObjectOpenHashSet<Object> seen = new ObjectOpenHashSet<>();
        final ObjectArrayList<Object> ordered = new ObjectArrayList<>();

        if (tree != null) {
            traverse(query, seen, ordered);
        }
        // 溢出区：构建后的帧内新增条目，线性补扫。
        for (int i = builtEntryCount, size = entries.size(); i < size; i++) {
            collectIfVisible(entries.get(i), query, seen, ordered);
        }

        return new SnapshotIterator(ordered.elements(), ordered.size());
    }

    /** 首次查询时物化扁平 BVH；构建以当前 avail 并集为叶子 AABB（已死条目直接排除）。 */
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
            // 叶子 AABB 取条目当前可用矩形的并集（保守超集，剪枝恒正确）。
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

    /** 显式 int 栈遍历扁平树，无递归、迭代期无分配。 */
    private void traverse(final int[] query,
                          final ObjectOpenHashSet<Object> seen,
                          final ObjectArrayList<Object> ordered) {
        final CollisionGridBvhBuild.Tree t = tree;
        int[] stack = traversalStack;
        int sp = 0;
        stack[sp++] = 0; // 根节点下标恒为 0

        while (sp > 0) {
            final int node = stack[--sp];
            if (query[0] > t.nMaxX[node] || t.nMinX[node] > query[2]
                    || query[1] > t.nMaxY[node] || t.nMinY[node] > query[3]) {
                continue;
            }

            if (t.nLeft[node] < 0) {
                // 叶子：逐条目检查可用区域。
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

    /** 条目可用区域与查询区间相交则收录（去重）。 */
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

    /** 单条登记项：对象引用 + 当前仍活跃的 cell 矩形列表（remove 逐步消耗）。 */
    private static final class Entry {
        final Object       object;
        List<int[]> avail;

        Entry(final Object object, final int[] addRange) {
            this.object = object;
            this.avail = new ArrayList<>(1);
            this.avail.add(addRange);
        }
    }

    /**
     * 快照迭代器：构造时结果已物化，后续增删不影响本次迭代；
     * {@link #remove()} 抛 {@link UnsupportedOperationException}（与原版一致）。
     */
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
