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
 * 默认使用 {@link CollisionGridBvh} 处理碰撞网格写入和查询。
 * {@code ssoptimizer.collisionGridBvh=false} 时保留均匀网格写入，并使用 fastutil 收集查询结果。
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

    @Overwrite(remap = false)
    public void addObject(Object object, Vector2f location, float width, float height) {
        if (CollisionGridBvhImpl.isEnabled()) {
            ssoptimizer$bvh().addObject(object, location.x, location.y, width, height);
            return;
        }
        ssoptimizer$writeCells(object, location, width, height, true);
    }

    @Overwrite(remap = false)
    public void removeObject(Object object, Vector2f location, float width, float height) {
        if (CollisionGridBvhImpl.isEnabled()) {
            ssoptimizer$bvh().removeObject(object, location.x, location.y, width, height);
            return;
        }
        ssoptimizer$writeCells(object, location, width, height, false);
    }

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
