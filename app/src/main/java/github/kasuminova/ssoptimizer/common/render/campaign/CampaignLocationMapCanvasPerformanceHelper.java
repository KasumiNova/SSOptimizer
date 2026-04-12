package github.kasuminova.ssoptimizer.common.render.campaign;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * 战役地图画布渲染热点优化 helper。
 * <p>
 * 职责：为战役地图图标缓存的保留操作提供线性时间的查找集合，避免原版在
 * {@code LinkedHashMap.keySet().retainAll(ArrayList)} 路径上退化成 O(m × n) 的重复 contains。<br>
 * 设计动机：热点报告显示 {@code CampaignLocationMapCanvas.renderStuff(float, boolean)} 中，
 * 这一步每帧都可能在大量实体上执行，最终把 CPU 时间集中烧在
 * {@link java.util.AbstractCollection#retainAll(Collection)}。<br>
 * 效果：当输入集合不是 {@link Set} 时，复用线程局部 {@link HashSet} 作为查找表，
 * 将保留操作降为近似 O(m + n)，同时保持与原始 {@code retainAll} 一致的 equals 语义。
 */
public final class CampaignLocationMapCanvasPerformanceHelper {
    private static final ThreadLocal<HashSet<Object>> RETAIN_LOOKUP_SET =
            ThreadLocal.withInitial(HashSet::new);

    private CampaignLocationMapCanvasPerformanceHelper() {
    }

    /**
     * 使用查找友好的集合执行保留操作，降低大集合场景下的 contains 开销。
     *
     * @param targetSet    需要原地保留元素的目标集合
     * @param liveEntities 当前仍然存活、应当被保留的实体集合
     * @return 若目标集合内容发生变化则返回 {@code true}
     */
    public static boolean retainLiveEntities(final Set<?> targetSet,
                                             final Collection<?> liveEntities) {
        if (targetSet.isEmpty()) {
            return false;
        }
        if (liveEntities.isEmpty()) {
            targetSet.clear();
            return true;
        }
        if (liveEntities instanceof Set<?>) {
            return targetSet.retainAll(liveEntities);
        }

        final HashSet<Object> lookup = RETAIN_LOOKUP_SET.get();
        lookup.clear();
        lookup.addAll(liveEntities);
        try {
            return targetSet.retainAll(lookup);
        } finally {
            lookup.clear();
        }
    }
}