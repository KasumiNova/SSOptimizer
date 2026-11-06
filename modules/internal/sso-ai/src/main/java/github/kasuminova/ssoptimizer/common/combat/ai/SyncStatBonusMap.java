package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.MutableStat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 用于 {@code StatBonus} 的同步 {@link LinkedHashMap}。
 * 单操作在实例锁上同步，集合视图返回独立快照且不支持通过视图回写；保留空键和空值语义。
 */
public final class SyncStatBonusMap extends LinkedHashMap<String, MutableStat.StatMod> {

    @Override
    public synchronized MutableStat.StatMod get(Object key) {
        return super.get(key);
    }

    @Override
    public synchronized MutableStat.StatMod put(String key, MutableStat.StatMod value) {
        return super.put(key, value);
    }

    @Override
    public synchronized void putAll(Map<? extends String, ? extends MutableStat.StatMod> m) {
        super.putAll(m);
    }

    @Override
    public synchronized MutableStat.StatMod remove(Object key) {
        return super.remove(key);
    }

    @Override
    public synchronized void clear() {
        super.clear();
    }

    @Override
    public synchronized boolean containsKey(Object key) {
        return super.containsKey(key);
    }

    @Override
    public synchronized boolean containsValue(Object value) {
        return super.containsValue(value);
    }

    @Override
    public synchronized int size() {
        return super.size();
    }

    @Override
    public synchronized boolean isEmpty() {
        return super.isEmpty();
    }

    @Override
    public synchronized Set<String> keySet() {
        return new LinkedHashSet<>(super.keySet());
    }

    @Override
    public synchronized Collection<MutableStat.StatMod> values() {
        return new ArrayList<>(super.values());
    }

    @Override
    public synchronized Set<Entry<String, MutableStat.StatMod>> entrySet() {
        Set<Entry<String, MutableStat.StatMod>> snapshot = new LinkedHashSet<>();
        for (Entry<String, MutableStat.StatMod> entry : super.entrySet()) {
            snapshot.add(new SimpleImmutableEntry<>(entry));
        }
        return snapshot;
    }
}
