package github.kasuminova.ssoptimizer.common.combat.ai.grid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * 碰撞网格三实现墙钟基准（main 方法，沿用 {@link CollisionGridCollectors} 的 checksum 消费风格）。
 * <p>
 * 场景（参照游戏实测负载：每帧 5 份网格全量重建 + 大量 AABB 查询）：
 * <ul>
 *     <li>网格范围 ±17000（地图 ±16000 + 1000 余量），cellSize=300；</li>
 *     <li>每帧 500 实体全量 add 重建 + 1000 次混合范围查询（0.3k/0.9k/1.5k 常规 + 少量 5k/40k）；</li>
 *     <li>单独测量 5000 范围大查询（避导弹扫描量级，100 次/帧）。</li>
 * </ul>
 * 三实现：
 * <ol>
 *     <li>原版网格（{@link VanillaGridReference}，算法逐行移植）；</li>
 *     <li>现有 fastutil 收集（原版 add + {@link CollisionGridQueryHelper} 查询，即 BVH 关闭时的回退路径）；</li>
 *     <li>BVH（{@link CollisionGridBvhImpl}，懒构建 + tombstone/溢出）。</li>
 * </ol>
 */
public final class CollisionGridBvhBenchmarkMain {
    private static final float GRID_MIN = -17000.0f;
    private static final float GRID_MAX = 17000.0f;
    private static final float CELL     = 300.0f;

    private static final int ENTITY_COUNT   = 500;
    private static final int MIXED_QUERIES  = 1000;
    private static final int LARGE_QUERIES  = 100;
    private static final int WARMUP_FRAMES  = 60;
    private static final int MEASURE_FRAMES = 300;

    public static void main(final String[] args) {
        final Random rng = new Random(20260816L);
        final List<Object> entities = new ArrayList<>(ENTITY_COUNT);
        final List<float[]> entityRects = new ArrayList<>(ENTITY_COUNT);
        for (int i = 0; i < ENTITY_COUNT; i++) {
            entities.add(new Object());
            entityRects.add(randomEntityRect(rng));
        }
        final List<float[]> mixedQueries = new ArrayList<>(MIXED_QUERIES);
        for (int i = 0; i < MIXED_QUERIES; i++) {
            mixedQueries.add(randomQueryRect(rng, false));
        }
        final List<float[]> largeQueries = new ArrayList<>(LARGE_QUERIES);
        for (int i = 0; i < LARGE_QUERIES; i++) {
            largeQueries.add(randomQueryRect(rng, true));
        }

        final long[] vanilla = benchmark(ImplKind.VANILLA, entities, entityRects, mixedQueries, largeQueries);
        final long[] fastutil = benchmark(ImplKind.FASTUTIL, entities, entityRects, mixedQueries, largeQueries);
        final long[] bvh = benchmark(ImplKind.BVH, entities, entityRects, mixedQueries, largeQueries);

        System.out.println("=== CollisionGrid 三实现墙钟基准（平均纳秒/帧，" + MEASURE_FRAMES + " 帧）===");
        System.out.println("场景A：500 实体重建 + 1000 次混合范围查询；场景B：100 次 5000 范围大查询");
        System.out.printf("%-28s %14s %14s %14s%n", "实现", "重建(add)", "混合查询", "5000大查询");
        printRow("原版网格 LinkedHashSet", vanilla);
        printRow("fastutil 收集（现有关闭路径）", fastutil);
        printRow("扁平 BVH（本次实现）", bvh);
    }

    private static void printRow(final String name, final long[] result) {
        System.out.printf("%-28s %14d %14d %14d   (checksum=%d)%n",
                name, result[0] / MEASURE_FRAMES, result[1] / MEASURE_FRAMES, result[2] / MEASURE_FRAMES, result[3]);
    }

    private enum ImplKind {VANILLA, FASTUTIL, BVH}

    /**
     * @return {@code [add 总ns, 混合查询总ns, 大查询总ns, checksum]}
     */
    private static long[] benchmark(final ImplKind kind,
                                    final List<Object> entities,
                                    final List<float[]> entityRects,
                                    final List<float[]> mixedQueries,
                                    final List<float[]> largeQueries) {
        long addNs = 0;
        long mixedNs = 0;
        long largeNs = 0;
        long checksum = 1;

        for (int frame = 0; frame < WARMUP_FRAMES + MEASURE_FRAMES; frame++) {
            final boolean measure = frame >= WARMUP_FRAMES;

            // 每帧全新网格实例（与游戏生命周期一致）。
            final VanillaGridReference grid =
                    new VanillaGridReference(GRID_MIN, GRID_MAX, GRID_MIN, GRID_MAX, CELL);
            final CollisionGridBvhImpl bvh = kind == ImplKind.BVH ? newBvh() : null;

            long t0 = System.nanoTime();
            for (int i = 0; i < entities.size(); i++) {
                final float[] r = entityRects.get(i);
                if (kind == ImplKind.BVH) {
                    bvh.addObject(entities.get(i), r[0], r[1], r[2], r[3]);
                } else {
                    grid.addObject(entities.get(i), r[0], r[1], r[2], r[3]);
                }
            }
            long t1 = System.nanoTime();
            if (measure) {
                addNs += t1 - t0;
            }

            for (final float[] q : mixedQueries) {
                checksum = 31L * checksum + consume(kind, grid, bvh, q);
            }
            t0 = System.nanoTime();
            long queryChecksum = 0;
            for (final float[] q : mixedQueries) {
                queryChecksum = 31L * queryChecksum + consume(kind, grid, bvh, q);
            }
            t1 = System.nanoTime();
            if (measure) {
                mixedNs += t1 - t0;
            }
            checksum = 31L * checksum + queryChecksum;

            t0 = System.nanoTime();
            long largeChecksum = 0;
            for (final float[] q : largeQueries) {
                largeChecksum = 31L * largeChecksum + consume(kind, grid, bvh, q);
            }
            t1 = System.nanoTime();
            if (measure) {
                largeNs += t1 - t0;
            }
            checksum = 31L * checksum + largeChecksum;
        }
        return new long[]{addNs, mixedNs, largeNs, checksum};
    }

    /** 消费一次查询结果并返回 checksum（防 JIT 消除）。 */
    private static long consume(final ImplKind kind,
                                final VanillaGridReference grid,
                                final CollisionGridBvhImpl bvh,
                                final float[] q) {
        final Iterator<Object> it;
        switch (kind) {
            case VANILLA:
                it = grid.getCheckIterator(q[0], q[1], q[2], q[3]);
                break;
            case FASTUTIL:
                it = CollisionGridQueryHelper.getCheckIterator(
                        grid.cells(), grid.gridWidth(), grid.gridHeight(),
                        grid.baseX(), grid.baseY(), grid.cellSize(),
                        q[0], q[1], q[2], q[3]);
                break;
            default:
                it = bvh.getCheckIterator(q[0], q[1], q[2], q[3]);
                break;
        }
        long sum = 0;
        while (it.hasNext()) {
            final Object next = it.next();
            sum += next == null ? 0 : next.hashCode();
        }
        return sum;
    }

    private static CollisionGridBvhImpl newBvh() {
        final int baseX = -((int) Math.floor(GRID_MIN / CELL));
        final int gridWidth = baseX + (int) Math.ceil(GRID_MAX / CELL);
        final int baseY = baseX;
        final int gridHeight = gridWidth;
        return new CollisionGridBvhImpl(baseX, baseY, gridWidth, gridHeight, CELL);
    }

    /** 实体矩形：位置覆盖网格全范围（含少量越界），尺寸 60~600，少量 5000。 */
    private static float[] randomEntityRect(final Random rng) {
        final float x = GRID_MIN - 1000.0f + rng.nextFloat() * (GRID_MAX - GRID_MIN + 2000.0f);
        final float y = GRID_MIN - 1000.0f + rng.nextFloat() * (GRID_MAX - GRID_MIN + 2000.0f);
        final float size = rng.nextInt(20) == 0 ? 5000.0f : 60.0f + rng.nextFloat() * 540.0f;
        return new float[]{x, y, size, size};
    }

    /** 查询矩形：常规 300/900/1500 混合；large 时固定 5000（避导弹扫描量级）。 */
    private static float[] randomQueryRect(final Random rng, final boolean large) {
        final float x = GRID_MIN + rng.nextFloat() * (GRID_MAX - GRID_MIN);
        final float y = GRID_MIN + rng.nextFloat() * (GRID_MAX - GRID_MIN);
        if (large) {
            return new float[]{x, y, 5000.0f, 5000.0f};
        }
        final float size;
        switch (rng.nextInt(10)) {
            case 0:
                size = 5000.0f;
                break;
            case 1:
                size = 40000.0f;
                break;
            case 2:
            case 3:
                size = 1500.0f;
                break;
            case 4:
            case 5:
            case 6:
                size = 900.0f;
                break;
            default:
                size = 300.0f;
                break;
        }
        return new float[]{x, y, size, size};
    }

    private CollisionGridBvhBenchmarkMain() {
    }
}
