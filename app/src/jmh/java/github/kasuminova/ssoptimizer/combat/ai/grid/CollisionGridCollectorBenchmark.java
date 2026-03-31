package github.kasuminova.ssoptimizer.combat.ai.grid;

import org.openjdk.jmh.annotations.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
public class CollisionGridCollectorBenchmark {
    @Benchmark
    public int linkedHashSetAddAll(final QueryState state) {
        return CollisionGridCollectors.linkedHashSetAddAllChecksum(
                state.cells,
                state.minX,
                state.maxX,
                state.minY,
                state.maxY
        );
    }

    @Benchmark
    public int manualLinkedHashSet(final QueryState state) {
        return CollisionGridCollectors.manualLinkedHashSetChecksum(
                state.cells,
                state.minX,
                state.maxX,
                state.minY,
                state.maxY
        );
    }

    @Benchmark
    public int hashSetAndArrayList(final QueryState state) {
        return CollisionGridCollectors.hashSetAndArrayListChecksum(
                state.cells,
                state.minX,
                state.maxX,
                state.minY,
                state.maxY
        );
    }

    @Benchmark
    public int fastutilOpenHashSetAndArrayList(final QueryState state) {
        return CollisionGridCollectors.fastutilOpenHashSetAndArrayListChecksum(
                state.cells,
                state.minX,
                state.maxX,
                state.minY,
                state.maxY
        );
    }

    @Benchmark
    public int fastutilLinkedOpenHashSet(final QueryState state) {
        return CollisionGridCollectors.fastutilLinkedOpenHashSetChecksum(
                state.cells,
                state.minX,
                state.maxX,
                state.minY,
                state.maxY
        );
    }

    @Benchmark
    public int helperIterator(final QueryState state) {
        return CollisionGridCollectors.helperIteratorChecksum(
                state.cells,
                state.gridWidth,
                state.gridHeight,
                state.baseX,
                state.baseY,
                state.cellSize,
                state.centerX,
                state.centerY,
                state.queryWidth,
                state.queryHeight
        );
    }

    @State(Scope.Thread)
    public static class QueryState {
        List<Object>[][] cells;
        int              gridWidth;
        int              gridHeight;
        int              minX;
        int              maxX;
        int              minY;
        int              maxY;
        int              baseX;
        int              baseY;
        float            cellSize;
        float            centerX;
        float            centerY;
        float            queryWidth;
        float            queryHeight;

        @Setup(Level.Trial)
        @SuppressWarnings("unchecked")
        public void setup() {
            gridWidth = 8;
            gridHeight = 8;
            baseX = 0;
            baseY = 0;
            cellSize = 100.0f;

            minX = 1;
            maxX = 6;
            minY = 1;
            maxY = 6;
            centerX = 350.0f;
            centerY = 350.0f;
            queryWidth = 500.0f;
            queryHeight = 500.0f;

            cells = (List<Object>[][]) new List[gridWidth][gridHeight];
            Random random = new Random(42L);
            ArrayList<Integer> valuePool = new ArrayList<>();
            int nextValue = 0;

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    ArrayList<Object> cell = new ArrayList<>(48);
                    for (int i = 0; i < 48; i++) {
                        final int value;
                        if (!valuePool.isEmpty() && random.nextFloat() < 0.45f) {
                            value = valuePool.get(random.nextInt(valuePool.size()));
                        } else {
                            value = nextValue;
                            nextValue++;
                            valuePool.add(value);
                        }
                        cell.add(new Probe(value));
                    }
                    cells[x][y] = cell;
                }
            }
        }
    }

    private record Probe(int value) {
    }
}