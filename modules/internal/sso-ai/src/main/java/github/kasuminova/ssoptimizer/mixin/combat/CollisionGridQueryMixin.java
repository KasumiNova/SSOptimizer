package github.kasuminova.ssoptimizer.mixin.combat;

import github.kasuminova.ssoptimizer.common.combat.ai.grid.CollisionGridBvh;
import github.kasuminova.ssoptimizer.common.combat.ai.grid.CollisionGridBvhImpl;
import github.kasuminova.ssoptimizer.common.combat.ai.grid.CollisionGridQueryHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.lwjgl.util.vector.Vector2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Iterator;
import java.util.List;

/**
 * 碰撞网格的 Mixin 重写。
 * <p>
 * 注入目标：{@code com.fs.starfarer.combat.CollisionGridQuery}<br>
 * 注入动机：原版均匀网格每帧 5 份全量重建（上万次 addToCell + ArrayList 分配），
 * 查询需逐 cell 扫描收集，在每帧大量 AI 碰撞查询时产生性能瓶颈。<br>
 * 注入效果：默认委托 {@link CollisionGridBvh}（懒构建扁平 BVH + 帧内 tombstone/溢出增量）；
 * {@code -Dssoptimizer.collisionGridBvh=false} 时 addObject/removeObject 走原版网格写入逻辑、
 * getCheckIterator 回退 {@link CollisionGridQueryHelper} 的 fastutil 收集路径。
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

    /** BVH 状态；懒初始化（网格实例每帧新建，无需跨帧重置）。 */
    @Unique
    private CollisionGridBvh ssoptimizer$bvh;

    @Unique
    private CollisionGridBvh ssoptimizer$bvh() {
        if (ssoptimizer$bvh == null) {
            ssoptimizer$bvh = new CollisionGridBvhImpl(
                    ssoptimizer$baseX, ssoptimizer$baseY,
                    ssoptimizer$gridWidth, ssoptimizer$gridHeight,
                    ssoptimizer$cellSize);
        }
        return ssoptimizer$bvh;
    }

    /**
     * 添加碰撞实体。
     *
     * @author KasumiNova
     * @reason BVH 开启时仅追加条目缓冲（懒构建）；关闭时逐位复刻原版 addObject 的网格写入。
     */
    @Overwrite(remap = false)
    public void addObject(Object object, Vector2f location, float width, float height) {
        if (CollisionGridBvhImpl.isEnabled()) {
            ssoptimizer$bvh().addObject(object, location.x, location.y, width, height);
            return;
        }
        ssoptimizer$writeCells(object, location, width, height, true);
    }

    /**
     * 移除碰撞实体。
     *
     * @author KasumiNova
     * @reason BVH 开启时按移除矩形消耗条目可用区域（幽灵条目语义与原版一致）；
     * 关闭时逐位复刻原版 removeObject 的网格移除。
     */
    @Overwrite(remap = false)
    public void removeObject(Object object, Vector2f location, float width, float height) {
        if (CollisionGridBvhImpl.isEnabled()) {
            ssoptimizer$bvh().removeObject(object, location.x, location.y, width, height);
            return;
        }
        ssoptimizer$writeCells(object, location, width, height, false);
    }

    /**
     * 区域碰撞查询。
     *
     * @author GitHub Copilot
     * @reason 原 ASM 处理器整体替换 getCheckIterator 方法体，迁移为等价的 @Overwrite；
     * 本次改为默认委托 BVH，关闭时保留 fastutil 收集回退路径。
     */
    @Overwrite(remap = false)
    public Iterator<Object> getCheckIterator(Vector2f center, float width, float height) {
        if (CollisionGridBvhImpl.isEnabled()) {
            return ssoptimizer$bvh().getCheckIterator(center.x, center.y, width, height);
        }
        return CollisionGridQueryHelper.getCheckIterator(
                ssoptimizer$cells, ssoptimizer$gridWidth, ssoptimizer$gridHeight,
                ssoptimizer$baseX, ssoptimizer$baseY, ssoptimizer$cellSize,
                center.x, center.y, width, height);
    }

    /**
     * 回退路径：原版 addObject/removeObject 的逐 cell 写入/移除（向零截断 + addToCell 式边界检查）。
     */
    @Unique
    private void ssoptimizer$writeCells(final Object object,
                                        final Vector2f location,
                                        final float width,
                                        final float height,
                                        final boolean add) {
        final int minX = (int) (ssoptimizer$baseX + (location.x - width / 2.0F) / ssoptimizer$cellSize);
        final int minY = (int) (ssoptimizer$baseY + (location.y - height / 2.0F) / ssoptimizer$cellSize);
        final int maxX = (int) (ssoptimizer$baseX + (location.x + width / 2.0F) / ssoptimizer$cellSize);
        final int maxY = (int) (ssoptimizer$baseY + (location.y + height / 2.0F) / ssoptimizer$cellSize);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                if (x < 0 || x >= ssoptimizer$gridWidth || y < 0 || y >= ssoptimizer$gridHeight) {
                    continue;
                }
                if (add) {
                    if (ssoptimizer$cells[x][y] == null) {
                        ssoptimizer$cells[x][y] = new java.util.ArrayList<>();
                    }
                    ssoptimizer$cells[x][y].add(object);
                } else if (ssoptimizer$cells[x][y] != null) {
                    ssoptimizer$cells[x][y].remove(object);
                }
            }
        }
    }
}
