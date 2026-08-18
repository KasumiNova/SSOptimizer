package github.kasuminova.ssoptimizer.common.combat.ai;

import com.fs.starfarer.api.combat.MutableStat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * StatBonus 三个 bonuses 表的线程安全 LinkedHashMap 替换实现。
 * <p>
 * 背景：AI 并行化后，worker 线程的 FiringSolutionEval 链路会读取敌方舰船
 * ShipStats（触发 {@code StatBonus.recompute}），与被读舰船自身 advance 所在
 * worker 线程的 modify/unmodify 系列写并发——原版 LinkedHashMap 并发 put+put /
 * put+iterate 会损坏哈希桶链表，压测实测 values() 迭代产出 null 元素导致
 * recompute NPE（"Cannot read field value because mod is null"）。
 * <p>
 * 设计：全部单操作在实例锁上同步（put/get/remove/clear/putAll/size 等），
 * 三个视图（values/keySet/entrySet）返回锁内快照拷贝——迭代永远不触碰活表，
 * 从结构上杜绝并发写损坏与 CME。保持 LinkedHashMap 子类（字段类型兼容），
 * null key/value 语义与原版一致（不引入 CHM 的 null 限制）。
 * 视图为快照而非活引用是对原版的已知语义变化：原版代码与模组均只做迭代读取，
 * 未见通过视图回写的用法。
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
