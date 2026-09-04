package github.kasuminova.ssoptimizer.common.render.campaign;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link TerrainTileRandomCache} 与原版 {@code new Random(seed)} 的序列等价性验证：
 * 同 seed 同调用次数的 nextFloat 输出必须位级一致（floatToRawIntBits 比较），
 * 覆盖 renderSubArea（3 次消费）与 renderQuad（21 次消费 + setSeed 抖动分支）
 * 两种原版用法。
 */
class TerrainTileRandomCacheTest {

    /** renderQuad 的每瓦片 nextFloat 消费峰值：angle 1 次 + 4 组 getThetaAndRadius 各 5 次。 */
    private static final int RENDER_QUAD_CONSUMPTION = 21;

    private static void assertNextFloatEquals(final Random expected, final Random actual) {
        assertEquals(Float.floatToRawIntBits(expected.nextFloat()),
                Float.floatToRawIntBits(actual.nextFloat()),
                "nextFloat 序列必须与真实 Random(seed) 位级一致");
    }

    @Test
    void replayMatchesRealRandomSequence() {
        long[] seeds = {0L, 1L, 405000000000L, -123456789000000L, Long.MAX_VALUE, Long.MIN_VALUE};
        for (long seed : seeds) {
            TerrainTileRandomCache cache = new TerrainTileRandomCache();
            Random cached = cache.random(seed);
            Random real = new Random(seed);
            for (int i = 0; i < RENDER_QUAD_CONSUMPTION; i++) {
                assertNextFloatEquals(real, cached);
            }
        }
    }

    @Test
    void reacquisitionReplaysFromStart() {
        TerrainTileRandomCache cache = new TerrainTileRandomCache();
        long seed = 42_000_000L;

        Random first = cache.random(seed);
        float[] firstRun = new float[RENDER_QUAD_CONSUMPTION];
        for (int i = 0; i < firstRun.length; i++) {
            firstRun[i] = first.nextFloat();
        }

        // 下一帧同瓦片再次「构造」：必须从头重放同一序列，且复用同一缓存条目
        Random second = cache.random(seed);
        assertEquals(1, cache.size(), "同 seed 必须复用同一缓存条目");
        for (float expected : firstRun) {
            assertEquals(Float.floatToRawIntBits(expected),
                    Float.floatToRawIntBits(second.nextFloat()),
                    "重放必须从头开始且逐值位级一致");
        }
    }

    @Test
    void shortConsumerSharesEntryWithLongConsumer() {
        // renderSubArea（3 次）与 renderQuad（21 次）的 seed 空间各自独立但共用
        // 同一缓存结构：短消费路径取序列前缀，与真实 Random 前 3 个值位级一致
        TerrainTileRandomCache cache = new TerrainTileRandomCache();
        long seed = 7_000_000L;
        Random cached = cache.random(seed);
        Random real = new Random(seed);
        for (int i = 0; i < 3; i++) {
            assertNextFloatEquals(real, cached);
        }
    }

    @Test
    void setSeedSwitchesToRealRandomEquivalent() {
        // renderQuad 的 flicker/shiver 抖动分支：rand.setSeed(动态种子) 后继续 nextFloat。
        // 原版语义 = 对同一 Random 对象 setSeed（重置全部状态），等价于全新 Random(动态种子)
        TerrainTileRandomCache cache = new TerrainTileRandomCache();
        Random cached = cache.random(99_000_000L);
        for (int i = 0; i < RENDER_QUAD_CONSUMPTION; i++) {
            cached.nextFloat();
        }

        long jitterSeed = 555_000_000L;
        cached.setSeed(jitterSeed);
        Random real = new Random(jitterSeed);
        for (int i = 0; i < 10; i++) {
            assertNextFloatEquals(real, cached);
        }

        // 再次 setSeed（下一帧抖动）：仍与全新 Random 位级一致
        long jitterSeed2 = 666_000_000L;
        cached.setSeed(jitterSeed2);
        Random real2 = new Random(jitterSeed2);
        for (int i = 0; i < 10; i++) {
            assertNextFloatEquals(real2, cached);
        }

        // setSeed 后重新获取（下一帧正常渲染）：必须恢复从头重放
        Random reacquired = cache.random(99_000_000L);
        Random realOriginal = new Random(99_000_000L);
        for (int i = 0; i < RENDER_QUAD_CONSUMPTION; i++) {
            assertNextFloatEquals(realOriginal, reacquired);
        }
    }

    @Test
    void exhaustionFailsLoudly() {
        TerrainTileRandomCache cache = new TerrainTileRandomCache();
        Random cached = cache.random(1_000_000L);
        for (int i = 0; i < TerrainTileRandomCache.SEQUENCE_LENGTH; i++) {
            cached.nextFloat();
        }
        assertThrows(IllegalStateException.class, cached::nextFloat,
                "超出预取序列必须立刻抛异常（构造上不可达，出现即实现破坏）");
    }
}
