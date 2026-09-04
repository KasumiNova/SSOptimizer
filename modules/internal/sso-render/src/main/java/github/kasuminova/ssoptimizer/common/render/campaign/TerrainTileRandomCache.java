package github.kasuminova.ssoptimizer.common.render.campaign;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Random;

/**
 * 战役瓦片地形逐瓦片随机序列缓存：消除 {@code BaseTiledTerrain.renderSubArea}
 * 与 {@code HyperspaceTerrainPlugin.renderQuad} 每帧每瓦片的 {@code new Random(seed)}
 * 构造与 nextFloat 链（named 源码 {@code BaseTiledTerrain.java:420/:489}、
 * {@code HyperspaceTerrainPlugin.java:267}）。
 * <p>
 * 语义等价性论证：两处热点的瓦片随机参数完全由 {@code seed} 确定性派生——
 * 原版每次以同一 seed 构造全新 {@link Random} 并从头消费 nextFloat 序列。
 * 本缓存按 seed 预取真实 {@code new Random(seed)} 的前
 * {@link #SEQUENCE_LENGTH} 个 nextFloat（一次性），随后每次「构造」返回按
 * seed 缓存的 {@link TileRandom} 并重置游标，从头重放同一序列——同样种子、
 * 同样调用次数，输出与原版位级一致（float 值原样存储重放，不经过任何再计算）。
 * <p>
 * 消费量核对（named 源码逐行）：
 * <ul>
 *   <li>{@code renderSubArea}（两条路径）：每瓦片恰好 3 次 nextFloat
 *       （angle/xOff/yOff）；</li>
 *   <li>{@code renderQuad}：1 次（angle）+ 4 × {@code getThetaAndRadius}
 *       （每次恰好 5 次 nextFloat：:219-221 theta 组 3 次、:224 theta 相位、
 *       :239 radius 相位）= 21 次；其后的 flicker/shiver 抖动分支先
 *       {@code setSeed(...)} 再消费——{@link TileRandom#setSeed} 切换为内部
 *       真实 {@code Random} 代理，行为与原版对同一 rand 对象 setSeed 后
 *       继续使用完全一致（vanilla 的 setSeed 本就重置全部状态）。</li>
 * </ul>
 * 持有方式：各 Mixin 的 {@code @Unique transient} 字段（每个地形实例一份），
 * transient 避免被 XStream 序列化；读档后字段为 null，首次渲染懒重建。
 * 渲染单线程逐瓦片顺序消费，同一缓存实例不存在并发/交错访问。
 */
public final class TerrainTileRandomCache {
    /**
     * 预取序列长度：renderQuad 峰值 21 次（1 + 4×5），renderSubArea 3 次，
     * 取 24 留一档余量。超出预取长度的消费在构造上不可达，
     * {@link TileRandom#nextFloat} 直接抛异常而非静默产出错误值。
     */
    static final int SEQUENCE_LENGTH = 24;

    private final Long2ObjectOpenHashMap<TileRandom> entries = new Long2ObjectOpenHashMap<>();

    /**
     * 取 seed 对应的瓦片随机实例（等价于原版 {@code new Random(seed)}）：
     * 首次构建并缓存，之后每次调用重置游标后复用同一实例（逐帧零分配）。
     *
     * @param seed 原版调用点的瓦片 seed
     * @return 从头重放 {@code new Random(seed)} nextFloat 序列的实例
     */
    public Random random(final long seed) {
        TileRandom entry = entries.get(seed);
        if (entry == null) {
            entry = new TileRandom(seed);
            entries.put(seed, entry);
        }
        entry.reset();
        return entry;
    }

    /** 缓存条目数（测试观测点）。 */
    public int size() {
        return entries.size();
    }

    /**
     * 单 seed 的 nextFloat 序列重放器。构造时用真实 {@link Random} 预取
     * {@link #SEQUENCE_LENGTH} 个 nextFloat 原样存储；{@link #reset()} 后
     * 从头重放。{@link #setSeed(long)} 后切换为内部真实 Random 代理
     * （与原版对同一 Random 对象 setSeed 后继续使用语义一致）。
     */
    static final class TileRandom extends Random {
        private final float[] sequence;
        private int cursor;
        private boolean replaying;
        /** setSeed 后的真实随机代理（仅 flicker/shiver 抖动分支触及，懒创建）。 */
        private Random fallback;

        TileRandom(final long seed) {
            // 无参 super() 构造期会虚拟调用 setSeed（此时字段尚未初始化）：
            // setSeed 内对 sequence 判空直接返回，super 内部种子状态不被使用，
            // 真正的序列由下方显式预取建立
            final Random source = new Random(seed);
            this.sequence = new float[SEQUENCE_LENGTH];
            for (int i = 0; i < this.sequence.length; i++) {
                this.sequence[i] = source.nextFloat();
            }
            reset();
        }

        /** 每次「构造」（{@link TerrainTileRandomCache#random} 命中）时调用：从头重放。 */
        void reset() {
            cursor = 0;
            replaying = true;
        }

        @Override
        public float nextFloat() {
            if (replaying) {
                if (cursor < sequence.length) {
                    return sequence[cursor++];
                }
                // 构造上不可达：原版各调用点峰值 21 次 < SEQUENCE_LENGTH；
                // 出错必须立刻可见，不得静默回落到错误序列
                throw new IllegalStateException(
                        "tile random replay sequence exhausted (length " + sequence.length + ")");
            }
            return fallback.nextFloat();
        }

        @Override
        public void setSeed(final long seed) {
            if (sequence == null) {
                // super() 无参构造期的虚拟 setSeed 调用：忽略（见构造器注释）
                return;
            }
            replaying = false;
            if (fallback == null) {
                fallback = new Random(seed);
            } else {
                fallback.setSeed(seed);
            }
        }
    }
}
