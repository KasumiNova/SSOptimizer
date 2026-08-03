package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.combat.ai.grid.CollisionGridQueryHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Iterator;
import java.util.List;

/**
 * 碰撞网格查询方法的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CollisionGridQuery#getCheckIterator(Vector2f, F, F)}<br>
 * 注入动机：原始实现存在冗余的迭代器分配和边界计算，在每帧大量 AI 碰撞查询时产生性能瓶颈。<br>
 * 注入效果：用 {@link CollisionGridQueryHelper} 的纯静态方法替换整个方法体，
 * 减少对象分配和边界检查开销。
 */
@Mixin(targets = GameClassNames.COLLISION_GRID_QUERY_DOTTED)
public abstract class CollisionGridQueryMixin {

    @Shadow(remap = false, aliases = "cells")
    private List<Object>[][] ssoptimizer$cells;

    @Shadow(remap = false, aliases = "gridWidth")
    private int ssoptimizer$gridWidth;

    @Shadow(remap = false, aliases = "gridHeight")
    private int ssoptimizer$gridHeight;

    @Shadow(remap = false, aliases = "baseX")
    private int ssoptimizer$baseX;

    @Shadow(remap = false, aliases = "baseY")
    private int ssoptimizer$baseY;

    @Shadow(remap = false, aliases = "cellSize")
    private float ssoptimizer$cellSize;

    /**
     * 将碰撞查询委托给纯静态 helper。
     *
     * @param center 查询中心
     * @param width  查询宽度
     * @param height 查询高度
     * @return 区域内不重复的碰撞实体迭代器
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 getCheckIterator 方法体，迁移为等价的 @Overwrite。
     */
    @Overwrite(remap = false)
    public Iterator<Object> getCheckIterator(Vector2f center, float width, float height) {
        return CollisionGridQueryHelper.getCheckIterator(
                ssoptimizer$cells, ssoptimizer$gridWidth, ssoptimizer$gridHeight,
                ssoptimizer$baseX, ssoptimizer$baseY, ssoptimizer$cellSize,
                center.x, center.y, width, height);
    }
}
