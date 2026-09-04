package github.kasuminova.ssoptimizer.common.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 兄弟锚点拓扑排序助手（{@code PositionImpl.sortChildren} 的等价重写）。
 * <p>
 * 原版算法：反复按原顺序扫描子列表，追加「锚点已就位（或无锚点）」的元素，
 * 成员判定用 {@code LinkedList.contains}——每轮扫描 O(n²)，轮数 = 锚链深度，
 * 总复杂度 O(轮数 × n²)。重构装配界面（RefitTab）重建成员列表时每个条目
 * 触发一次 {@code recompute → sortChildren}，组件数百级时单次对话框打开
 * 可达秒级阻塞（JProfiler 实测 {@code LinkedList.contains} 占该路径 48%+）。<br>
 * 本实现与原版逐点等价：
 * <ul>
 *   <li>结果序：同一「按原顺序多轮扫描、锚点已就位即追加」的稳定拓扑序，
 *       仅成员判定从线性扫描换成集合（{@code PositionImpl} 未覆写
 *       {@code equals/hashCode}，LinkedList.contains 与 HashSet 同为恒等语义）；</li>
 *   <li>锚点非 null 且不在列表中：抛 {@code RuntimeException("May only anchor on
 *       siblings")}（原版仅在元素未就位时检查，本实现同样只在未就位分支检查）；</li>
 *   <li>轮数超过列表大小仍未排完：抛 {@code RuntimeException("Circular dependency
 *       of sibling positions detected")}（重复元素/自锚/锚环同原版走此出口）；</li>
 *   <li>小列表（&lt; {@link #SMALL_SET_THRESHOLD}）不开集合，直接对结果
 *       ArrayList 做 contains——UI 布局绝大多数节点子数 ≤5，避免每次
 *       recompute 分配两个 HashSet 反而劣化常见路径。</li>
 * </ul>
 * 泛型化动机：算法本体不依赖游戏类，锚点取值由调用方注入
 * （{@code PositionImpl::getBase}），便于脱离游戏环境做逻辑单测。
 */
public final class PositionSortHelper {
    /** 小列表阈值：低于此不分配 HashSet，直接线性 contains。 */
    static final int SMALL_SET_THRESHOLD = 16;

    private PositionSortHelper() {
    }

    /**
     * 按「锚点必须先于锚定者」原地重排 {@code items}。
     *
     * @param items    待排序列表（原地改写；恒等语义，不得含需按 equals 去重的元素）
     * @param anchorOf 锚点取值（返回 null 表示无锚点）
     */
    public static <T> void sortByAnchor(final List<T> items, final Function<T, T> anchorOf) {
        final int count = items.size();
        if (count == 0) {
            return;
        }
        final boolean large = count >= SMALL_SET_THRESHOLD;
        final Set<T> itemSet = large ? new HashSet<>(items) : null;
        final Set<T> placedSet = large ? new HashSet<>(count * 2) : null;
        final List<T> sorted = new ArrayList<>(count);

        int passes = 0;
        while (sorted.size() < count) {
            for (final T item : items) {
                if (contains(placedSet, sorted, item)) {
                    continue;
                }
                final T anchor = anchorOf.apply(item);
                if (anchor != null && !contains(itemSet, items, anchor)) {
                    throw new RuntimeException("May only anchor on siblings");
                }
                if (anchor == null || contains(placedSet, sorted, anchor)) {
                    sorted.add(item);
                    if (placedSet != null) {
                        placedSet.add(item);
                    }
                }
            }
            if (++passes > count) {
                throw new RuntimeException("Circular dependency of sibling positions detected");
            }
        }

        items.clear();
        items.addAll(sorted);
    }

    /** 成员判定：大列表走集合，小列表走线性扫描（与原版 LinkedList.contains 同语义）。 */
    private static <T> boolean contains(final Set<T> set, final List<T> list, final T item) {
        return set != null ? set.contains(item) : list.contains(item);
    }
}
