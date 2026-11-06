package github.kasuminova.ssoptimizer.common.loading;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * 所有操作同步于自身；失效键不在每次读取时清理，而由写操作、遍历及定期读取抽样批量回收。
 */
final class WeakKeyMap<K, V> {
    private static final int   DEFAULT_INITIAL_CAPACITY = 16;
    private static final int   MAXIMUM_CAPACITY         = 1 << 30;
    private static final float LOAD_FACTOR              = 0.75f;
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

    synchronized void clear() {
        Arrays.fill(table, null);
        size = 0;
        while (queue.poll() != null) {
        }
    }

    synchronized int size() {
        if (size == 0) {
            return 0;
        }
        expungeStaleEntries();
        return size;
    }

    synchronized boolean isEmpty() {
        return size == 0;
    }

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
