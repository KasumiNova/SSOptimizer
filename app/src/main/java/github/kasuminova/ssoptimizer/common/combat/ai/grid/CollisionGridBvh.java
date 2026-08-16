package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import java.util.Iterator;

/**
 * 碰撞网格的扁平 BVH 查询结构。
 * <p>
 * 替代原版 {@code CollisionGridQuery} 的均匀网格（{@code List<Object>[][] cells}）实现，
 * 贴合游戏「每帧新建网格实例 → 全量 addObject → 大量 AABB 查询 → 帧内交替 remove/add」的生命周期：
 * <ul>
 *     <li>{@link #addObject} 只追加条目缓冲并置脏，首次 {@link #getCheckIterator} 若脏则一次性物化扁平 BVH
 *         （懒构建；整帧无人查询的网格零构建成本）；</li>
 *     <li>构建后的帧内 add 进入溢出列表，查询时线性补扫；</li>
 *     <li>{@link #removeObject} 逐位复刻原版 remove 语义（含幽灵条目行为），按 cell 矩形消耗条目可用区域。</li>
 * </ul>
 * 语义等价保证（相对原版实现）：
 * <ul>
 *     <li>所有区间为 cell 整数空间相交超集，世界坐标精确 AABB 过滤仍由调用方负责；</li>
 *     <li>cell 区间计算逐位复刻原版 {@code (int)(base + x / cellSize)} 向零截断与越界 clamp；</li>
 *     <li>null 条目允许；同一对象重复 add 在查询结果中去重；</li>
 *     <li>查询结果在调用时快照，迭代进行中的增删不影响已返回的迭代器；</li>
 *     <li>返回迭代器的 {@link Iterator#remove()} 抛 {@link UnsupportedOperationException}；
 *         空结果返回空迭代器。</li>
 * </ul>
 * 已知偏差：迭代顺序为 BVH Morton 序，原版为 cell 行优先扫描序；游戏内调用方均自行做精确过滤，
 * 不依赖迭代顺序。
 * <p>
 * 实现：{@link CollisionGridBvhImpl}；构建算法：{@link CollisionGridBvhBuild}。
 */
public interface CollisionGridBvh {

    /**
     * 向网格添加一个对象。坐标语义与原版 {@code CollisionGridQuery#addObject} 一致：
     * 以 ({@code centerX}, {@code centerY}) 为中心、{@code width}×{@code height} 为宽高的矩形。
     * <p>
     * 完全落在网格外的对象不会被登记（与原版 addToCell 越界跳过一致）。
     *
     * @param object  碰撞实体，允许 null
     * @param centerX 中心世界坐标 X
     * @param centerY 中心世界坐标 Y
     * @param width   矩形宽度
     * @param height  矩形高度
     */
    void addObject(Object object, float centerX, float centerY, float width, float height);

    /**
     * 从网格移除一个对象。逐位复刻原版 {@code CollisionGridQuery#removeObject} 语义：
     * 按移除矩形逐 cell 消耗该对象的一个登记项；当移除区间与添加区间不一致时，
     * 未被覆盖的 cell 中保留幽灵条目（与原版一致，刻意保留）。
     *
     * @param object  碰撞实体，按原版 {@code List.remove} 的 equals 语义匹配
     * @param centerX 中心世界坐标 X
     * @param centerY 中心世界坐标 Y
     * @param width   矩形宽度
     * @param height  矩形高度
     */
    void removeObject(Object object, float centerX, float centerY, float width, float height);

    /**
     * 查询与给定矩形在 cell 空间相交的所有不重复对象（快照迭代器）。
     *
     * @param centerX 查询中心世界坐标 X
     * @param centerY 查询中心世界坐标 Y
     * @param width   查询宽度
     * @param height  查询高度
     * @return 结果快照迭代器；{@link Iterator#remove()} 抛 {@link UnsupportedOperationException}
     */
    Iterator<Object> getCheckIterator(float centerX, float centerY, float width, float height);
}
