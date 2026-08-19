package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 原版 {@code com.fs.starfarer.combat.CollisionGridQuery} 的逐行移植参考实现（测试夹具）。
 * <p>
 * 算法与 deob 源码一一对应（向零截断 cell 映射、addToCell 越界跳过、removeFromCell 按
 * {@code List.remove} 消耗、查询构造时 LinkedHashSet 快照、迭代器 remove() 抛异常），
 * 仅把 {@code Vector2f} 参数拆为 float 以避免测试依赖游戏 jar。
 */
final class VanillaGridReference {
    private final float            cellSize;
    private final List<Object>[][] cells;
    private final int              gridWidth;
    private final int              gridHeight;
    private final int              baseX;
    private final int              baseY;

    @SuppressWarnings("unchecked")
    VanillaGridReference(final float minX, final float maxX, final float minY, final float maxY, final float cellSize) {
        this.cellSize = cellSize;
        this.baseX = -((int) Math.floor(minX / cellSize));
        final int maxCellsX = (int) Math.ceil(maxX / cellSize);
        this.baseY = -((int) Math.floor(minY / cellSize));
        final int maxCellsY = (int) Math.ceil(maxY / cellSize);
        this.gridWidth = this.baseX + maxCellsX;
        this.gridHeight = this.baseY + maxCellsY;
        this.cells = (List<Object>[][]) new List[this.gridWidth][this.gridHeight];
    }

    void addObject(final Object object, final float x, final float y, final float w, final float h) {
        final int minX = (int) (this.baseX + (x - w / 2.0F) / this.cellSize);
        final int minY = (int) (this.baseY + (y - h / 2.0F) / this.cellSize);
        final int maxX = (int) (this.baseX + (x + w / 2.0F) / this.cellSize);
        final int maxY = (int) (this.baseY + (y + h / 2.0F) / this.cellSize);

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                addToCell(cx, cy, object);
            }
        }
    }

    void removeObject(final Object object, final float x, final float y, final float w, final float h) {
        final int minX = (int) (this.baseX + (x - w / 2.0F) / this.cellSize);
        final int minY = (int) (this.baseY + (y - h / 2.0F) / this.cellSize);
        final int maxX = (int) (this.baseX + (x + w / 2.0F) / this.cellSize);
        final int maxY = (int) (this.baseY + (y + h / 2.0F) / this.cellSize);

        for (int cx = minX; cx <= maxX; cx++) {
            for (int cy = minY; cy <= maxY; cy++) {
                removeFromCell(cx, cy, object);
            }
        }
    }

    Iterator<Object> getCheckIterator(final float x, final float y, final float w, final float h) {
        final int minX = (int) (this.baseX + (x - w / 2.0F) / this.cellSize);
        final int minY = (int) (this.baseY + (y - h / 2.0F) / this.cellSize);
        final int maxX = (int) (this.baseX + (x + w / 2.0F) / this.cellSize);
        final int maxY = (int) (this.baseY + (y + h / 2.0F) / this.cellSize);
        return new GridCheckIterator(minX, maxX, minY, maxY);
    }

    private void addToCell(final int x, final int y, final Object object) {
        if (x >= 0 && x < this.gridWidth && y >= 0 && y < this.gridHeight) {
            if (this.cells[x][y] == null) {
                this.cells[x][y] = new ArrayList<>();
            }
            this.cells[x][y].add(object);
        }
    }

    private void removeFromCell(final int x, final int y, final Object object) {
        if (x >= 0 && x < this.gridWidth && y >= 0 && y < this.gridHeight) {
            if (this.cells[x][y] != null) {
                this.cells[x][y].remove(object);
            }
        }
    }

    // ---- 供基准测试访问的网格内部状态 ----

    List<Object>[][] cells() {
        return cells;
    }

    int gridWidth() {
        return gridWidth;
    }

    int gridHeight() {
        return gridHeight;
    }

    int baseX() {
        return baseX;
    }

    int baseY() {
        return baseY;
    }

    float cellSize() {
        return cellSize;
    }

    private final class GridCheckIterator implements Iterator<Object> {
        private final Iterator<Object> innerIterator;

        GridCheckIterator(int minX, int maxX, int minY, int maxY) {
            final LinkedHashSet<Object> cellsSet = new LinkedHashSet<>();
            if (minX < 0) {
                minX = 0;
            }
            if (maxX >= gridWidth) {
                maxX = gridWidth - 1;
            }
            if (minY < 0) {
                minY = 0;
            }
            if (maxY >= gridHeight) {
                maxY = gridHeight - 1;
            }
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    if (cells[x][y] != null) {
                        cellsSet.addAll(cells[x][y]);
                    }
                }
            }
            this.innerIterator = cellsSet.iterator();
        }

        @Override
        public boolean hasNext() {
            return innerIterator.hasNext();
        }

        @Override
        public Object next() {
            return innerIterator.next();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
