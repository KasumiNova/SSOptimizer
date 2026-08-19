package github.kasuminova.ssoptimizer.common.loading;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * 弱键哈希映射：键仅被弱引用持有（键对象被 GC 回收后条目可被清理），值强引用持有。
 * <p>
 * 动机（v49 profile，847 样本 96% 集中在 resolveTrackedResourcePath /
 * ensureTextureReady / requiresContextReload）：原 {@code Collections.synchronizedMap
 * (new WeakHashMap<>())} 的每次 get 都会先执行 {@link java.util.WeakHashMap} 内部的
 * {@code expungeStaleEntries}（对 {@link ReferenceQueue} 做一次 poll），该开销在
 * LazyTextureManager 逐纹理绑定的高频查询路径上不可忽略。本类把清理从读路径剥离：
 * get 只做无副作用的表探测——已清空键的条目在 {@link #eq} 比较中天然失配，不参与
 * 匹配；过期条目由以下低频路径批量回收：
 * <ul>
 *     <li>写操作（{@link #put}/{@link #remove}）进入前 expunge 一次；</li>
 *     <li>{@link #size()}（非零时）与 {@link #forEach} 进入前 expunge 一次；</li>
 *     <li>get 每 {@value #GET_SWEEP_INTERVAL} 次顺带 expunge 一次，约束长时间
 *         只读段内死条目的堆积。</li>
 * </ul>
 * 相对原结构（synchronizedMap + WeakHashMap）消除了读路径的 expunge 与
 * ReferenceQueue.poll；键被 GC 后条目不泄漏的语义与 WeakHashMap 一致（清理为惰性，
 * 只影响滞后时间，不影响查询结果）。
 * <p>
 * 线程安全：所有方法同步于自身（与 {@code Collections.synchronizedMap} 相同的串行
 * 语义），调用方仍可对同一实例做同步复合操作（如 get-then-put）。
 * <p>
 * 不支持 null 键（本项目调用点均为非空 TextureObject）。
 */
final class WeakKeyMap<K, V> {
    private static final int   DEFAULT_INITIAL_CAPACITY = 16;
    private static final int   MAXIMUM_CAPACITY         = 1 << 30;
    private static final float LOAD_FACTOR              = 0.75f;
    /** get 每累计多少次顺带 expunge 一次（低频批量清理，读路径摊销成本可忽略）。 */
    private static final int   GET_SWEEP_INTERVAL       = 4096;

    private Entry<K, V>[] table;
    private int           size;
    private int           threshold;
    private int           getCount;
    private final ReferenceQueue<K> queue = new ReferenceQueue<>();

    WeakKeyMap() {
        table = newTable(DEFAULT_INITIAL_CAPACITY);
        threshold = (int) (DEFAULT_INITIAL_CAPACITY * LOAD_FACTOR);
    }

    /**
     * 查询键对应的值。不触发 expunge：死条目在探测中失配被跳过，
     * 清理由 {@link #expungeStaleEntries} 的低频路径承担。
     *
     * @return 值；键不存在或对应键已被 GC 时返回 null
     */
    synchronized V get(final K key) {
        if ((++getCount & (GET_SWEEP_INTERVAL - 1)) == 0) {
            expungeStaleEntries();
        }
        final int hash = hash(key);
        final int index = indexFor(hash, table.length);
        for (Entry<K, V> e = table[index]; e != null; e = e.next) {
            if (e.hash == hash && eq(key, e.get())) {
                return e.value;
            }
        }
        return null;
    }

    /**
     * 写入键值对；键已存在（按 WeakHashMap 同款 equals 语义）时覆盖值。
     *
     * @return 旧值；键不存在时返回 null
     */
    synchronized V put(final K key, final V value) {
        expungeStaleEntries();
        final int hash = hash(key);
        final int index = indexFor(hash, table.length);
        for (Entry<K, V> e = table[index]; e != null; e = e.next) {
            if (e.hash == hash && eq(key, e.get())) {
                final V old = e.value;
                e.value = value;
                return old;
            }
        }
        table[index] = new Entry<>(key, value, queue, hash, table[index]);
        size++;
        if (size >= threshold) {
            resize();
        }
        return null;
    }

    /**
     * 移除键对应条目。
     *
     * @return 被移除的值；键不存在时返回 null
     */
    synchronized V remove(final K key) {
        expungeStaleEntries();
        final int hash = hash(key);
        final int index = indexFor(hash, table.length);
        Entry<K, V> prev = table[index];
        Entry<K, V> e = prev;
        while (e != null) {
            final Entry<K, V> next = e.next;
            if (e.hash == hash && eq(key, e.get())) {
                size--;
                if (prev == e) {
                    table[index] = next;
                } else {
                    prev.next = next;
                }
                return e.value;
            }
            prev = e;
            e = next;
        }
        return null;
    }

    /** 清空全部条目（含排空引用队列，键即刻全部释放）。 */
    synchronized void clear() {
        Arrays.fill(table, null);
        size = 0;
        while (queue.poll() != null) {
            // 仅排空队列
        }
    }

    /** @return 存活条目数（非零时先 expunge，与 WeakHashMap 的 size 语义一致） */
    synchronized int size() {
        if (size == 0) {
            return 0;
        }
        expungeStaleEntries();
        return size;
    }

    /** @return 是否无存活条目 */
    synchronized boolean isEmpty() {
        return size == 0;
    }

    /**
     * 对每个键仍存活的条目执行 {@code action}。进入前 expunge 一次；
     * 迭代期间被 GC 清空的键按弱一致语义跳过（与 WeakHashMap 迭代器一致）。
     */
    synchronized void forEach(final BiConsumer<K, V> action) {
        expungeStaleEntries();
        for (final Entry<K, V> entry : table) {
            for (Entry<K, V> e = entry; e != null; e = e.next) {
                final K key = e.get();
                if (key != null) {
                    action.accept(key, e.value);
                }
            }
        }
    }

    /**
     * 排空引用队列并摘除全部死条目。仅由写操作、size/forEach 与 get 的低频
     * 抽样调用，绝不在每次 get 上执行（替换 WeakHashMap 每访问一次 expunge
     * 的核心差异）。
     */
    private void expungeStaleEntries() {
        for (Object x; (x = queue.poll()) != null; ) {
            @SuppressWarnings("unchecked")
            final Entry<K, V> entry = (Entry<K, V>) x;
            final int index = indexFor(entry.hash, table.length);
            Entry<K, V> prev = table[index];
            Entry<K, V> e = prev;
            while (e != null) {
                final Entry<K, V> next = e.next;
                if (e == entry) {
                    size--;
                    if (prev == e) {
                        table[index] = next;
                    } else {
                        prev.next = next;
                    }
                    break;
                }
                prev = e;
                e = next;
            }
        }
    }

    private void resize() {
        final Entry<K, V>[] oldTable = table;
        final int oldCapacity = oldTable.length;
        if (oldCapacity >= MAXIMUM_CAPACITY) {
            threshold = Integer.MAX_VALUE;
            return;
        }
        final int newCapacity = oldCapacity << 1;
        final Entry<K, V>[] newTable = newTable(newCapacity);
        for (final Entry<K, V> entry : oldTable) {
            for (Entry<K, V> e = entry; e != null; ) {
                final Entry<K, V> next = e.next;
                final int index = indexFor(e.hash, newCapacity);
                e.next = newTable[index];
                newTable[index] = e;
                e = next;
            }
        }
        table = newTable;
        threshold = (int) (newCapacity * LOAD_FACTOR);
    }

    private static int hash(final Object key) {
        final int h = key.hashCode();
        return h ^ (h >>> 16);
    }

    /** 与 WeakHashMap 相同的键比较语义：同一性或 equals。 */
    private static boolean eq(final Object x, final Object y) {
        return x == y || x.equals(y);
    }

    private static int indexFor(final int hash, final int length) {
        return hash & (length - 1);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Entry<K, V>[] newTable(final int capacity) {
        return (Entry<K, V>[]) new Entry<?, ?>[capacity];
    }

    /** 表条目：弱引用键（入队 {@code queue}）+ 强引用值 + 冲突链。 */
    private static final class Entry<K, V> extends WeakReference<K> {
        final int      hash;
        V              value;
        Entry<K, V>    next;

        Entry(final K key, final V value, final ReferenceQueue<K> queue,
              final int hash, final Entry<K, V> next) {
            super(key, queue);
            this.value = value;
            this.hash = hash;
            this.next = next;
        }
    }
}
