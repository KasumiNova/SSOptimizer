package github.kasuminova.ssoptimizer.combat.ai.grid;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

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