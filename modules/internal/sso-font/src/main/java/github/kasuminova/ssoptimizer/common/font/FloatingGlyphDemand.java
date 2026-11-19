package github.kasuminova.ssoptimizer.common.font;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;

final class FloatingGlyphDemand<K> {
    enum Action { ENQUEUE, WAIT, ORIGINAL }
    private final int limit;
    private final LinkedHashMap<K, Integer> hits = new LinkedHashMap<>(64, 0.75f, true);
    private final HashSet<K> unavailable = new HashSet<>();
    private boolean full;

    FloatingGlyphDemand(int limit) { this.limit = limit; }

    static <V> Map.Entry<Integer, V> nearest(NavigableMap<Integer, V> sizes, int target) {
        var hi = sizes.ceilingEntry(target);
        var lo = sizes.floorEntry(target);
        var best = lo == null || (hi != null && hi.getKey() - target <= target - lo.getKey()) ? hi : lo;
        return best != null && Math.abs(best.getKey() - target) * 4 <= target ? best : null;
    }

    Action plan(K key, boolean cold, boolean pending, boolean busy) {
        if (pending) return Action.WAIT;
        if (full || unavailable.contains(key)) return Action.ORIGINAL;
        int n = hits.getOrDefault(key, 0);
        if (!cold && n < 2) {
            hits.put(key,n + 1);
            if (hits.size() > limit) hits.pollFirstEntry();
            return Action.WAIT;
        }
        if (busy) return Action.WAIT;
        hits.remove(key);
        return Action.ENQUEUE;
    }

    void failed(K key) {
        if (unavailable.size() < limit) unavailable.add(key);
        else full = true;
    }

    void filled() { full = true; }

    boolean full() { return full; }

    void invalidate() { hits.clear(); unavailable.clear(); full = false; }
}
