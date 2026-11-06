package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import java.util.Iterator;

/**
 * 碰撞网格的 BVH 查询契约。
 * 查询返回 cell 空间相交的候选快照，调用方负责精确碰撞过滤；增删不影响已返回的迭代器。
 * 重复对象会去重，移除语义与原版一致，但不保证迭代顺序。
 */
public interface CollisionGridBvh {

    /**
     * 添加以指定世界坐标为中心的矩形对象，完全落在网格外的对象不会登记。
     * 坐标转换与原版一致，{@code object} 允许为 {@code null}。
     */
    void addObject(Object object, float centerX, float centerY, float width, float height);

    /**
     * 按 {@code equals} 匹配并消耗移除矩形覆盖的登记区域。
     * 添加与移除矩形不一致时保留未覆盖区域，与原版幽灵条目行为一致。
     */
    void removeObject(Object object, float centerX, float centerY, float width, float height);

    /**
     * 返回与指定矩形在 cell 空间相交的不重复对象快照。
     * 返回迭代器的 {@link Iterator#remove()} 抛 {@link UnsupportedOperationException}。
     */
    Iterator<Object> getCheckIterator(float centerX, float centerY, float width, float height);
}
