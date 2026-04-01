package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 碰撞网格查询工具类。
 * <p>
 * 将浮点坐标的矩形区域映射到网格单元格索引，
 * 并调用 {@link CollisionGridCollectors} 收集区域内所有不重复的碰撞实体。
 * 由 ASM 注入的 {@code CollisionGridQueryProcessor} 在运行时替换原版引擎的查询逻辑。
 */
public final class CollisionGridQueryHelper {
    private CollisionGridQueryHelper() {
    }

    public static Iterator<Object> getCheckIterator(final List<Object>[][] cells,
                                                    final int gridWidth,
                                                    final int gridHeight,
                                                    final int baseX,
                                                    final int baseY,
                                                    final float cellSize,
                                                    final float centerX,
                                                    final float centerY,
                                                    final float width,
                                                    final float height) {
        if (cells == null || gridWidth <= 0 || gridHeight <= 0 || cellSize == 0.0f) {
            return Collections.emptyIterator();
        }

        int minX = (int) (baseX + ((centerX - width * 0.5f) / cellSize));
        int minY = (int) (baseY + ((centerY - height * 0.5f) / cellSize));
        int maxX = (int) (baseX + ((centerX + width * 0.5f) / cellSize));
        int maxY = (int) (baseY + ((centerY + height * 0.5f) / cellSize));

        if (minX < 0) {
            minX = 0;
        }
        if (minY < 0) {
            minY = 0;
        }
        if (maxX >= gridWidth) {
            maxX = gridWidth - 1;
        }
        if (maxY >= gridHeight) {
            maxY = gridHeight - 1;
        }
        if (minX > maxX || minY > maxY) {
            return Collections.emptyIterator();
        }

        return CollisionGridCollectors.collectDistinctOrdered(cells, minX, maxX, minY, maxY);
    }
}