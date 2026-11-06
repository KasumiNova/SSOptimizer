package github.kasuminova.ssoptimizer.common.render.campaign;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Random;

public final class TerrainTileRandomCache {
    static final int SEQUENCE_LENGTH = 24;

    private final Long2ObjectOpenHashMap<TileRandom> entries = new Long2ObjectOpenHashMap<>();

    public Random random(final long seed) {
        TileRandom entry = entries.get(seed);
        if (entry == null) {
            entry = new TileRandom(seed);
            entries.put(seed, entry);
        }
        entry.reset();
        return entry;
    }

    public int size() {
        return entries.size();
    }

    static final class TileRandom extends Random {
        private final float[] sequence;
        private int cursor;
        private boolean replaying;
        private Random fallback;

        TileRandom(final long seed) {
            final Random source = new Random(seed);
            this.sequence = new float[SEQUENCE_LENGTH];
            for (int i = 0; i < this.sequence.length; i++) {
                this.sequence[i] = source.nextFloat();
            }
            reset();
        }

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
                throw new IllegalStateException(
                        "tile random replay sequence exhausted (length " + sequence.length + ")");
            }
            return fallback.nextFloat();
        }

        @Override
        public void setSeed(final long seed) {
            // Random 构造器会虚拟调用 setSeed，此时 sequence 尚未初始化。
            if (sequence == null) {
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
