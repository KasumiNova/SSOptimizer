package github.kasuminova.ssoptimizer.common.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * 按兄弟锚点稳定拓扑排序，使用恒等语义判断成员资格。
 * 非兄弟锚点与循环依赖沿用原版异常；小列表使用线性查找以避免集合分配。
 */
public final class PositionSortHelper {
    static final int SMALL_SET_THRESHOLD = 16;

    private PositionSortHelper() {
    }

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

    private static <T> boolean contains(final Set<T> set, final List<T> list, final T item) {
        return set != null ? set.contains(item) : list.contains(item);
    }
}
