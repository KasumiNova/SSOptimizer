package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WeakKeyMap} 语义测试：基础增删改查 + 键被 GC 后条目不泄漏（替换
 * WeakHashMap 时要求保持的核心语义）。
 */
class WeakKeyMapTest {

    @Test
    void putGetReplaceRemove() {
        final WeakKeyMap<String, String> map = new WeakKeyMap<>();
        final String key = "key";

        assertNull(map.put(key, "v1"));
        assertEquals("v1", map.get(key));
        // 同键覆盖（equals 语义）。
        assertEquals("v1", map.put(key, "v2"));
        assertEquals("v2", map.get(key));

        assertEquals("v2", map.remove(key));
        assertNull(map.get(key));
        assertNull(map.remove(key));
        assertTrue(map.isEmpty());
    }

    @Test
    void entriesWithDistinctKeysAreIsolated() {
        final WeakKeyMap<Object, String> map = new WeakKeyMap<>();
        final Object a = new Object();
        final Object b = new Object();
        map.put(a, "a");
        map.put(b, "b");

        assertEquals(2, map.size());
        assertEquals("a", map.get(a));
        assertEquals("b", map.get(b));
        map.remove(a);
        assertNull(map.get(a));
        assertEquals("b", map.get(b));
    }

    @Test
    void clearDropsAllEntries() {
        final WeakKeyMap<Object, String> map = new WeakKeyMap<>();
        final Object a = new Object();
        final Object b = new Object();
        map.put(a, "a");
        map.put(b, "b");

        map.clear();
        assertTrue(map.isEmpty());
        assertNull(map.get(a));
        assertNull(map.get(b));
    }

    @Test
    void forEachVisitsOnlyLiveEntries() {
        final WeakKeyMap<Object, String> map = new WeakKeyMap<>();
        final Object a = new Object();
        final Object b = new Object();
        map.put(a, "a");
        map.put(b, "b");

        // 哈希表无迭代顺序保证，按集合比较。
        final Set<String> seen = new HashSet<>();
        map.forEach((key, value) -> seen.add(value));
        assertEquals(Set.of("a", "b"), seen);
    }

    @Test
    void entriesAreDroppedAfterKeyIsCollected() {
        final WeakKeyMap<Object, String> map = new WeakKeyMap<>();
        final WeakReference<Object> weak = new WeakReference<>(putAndReturnKey(map, "value"));
        assertSame("value", map.get(weak.get()));
        assertEquals(1, map.size());

        forceGcUntil(map, weak, 0);
        assertNull(weak.get(), "键对象应已被 GC 回收");

        // size() 先 expunge：键已回收的条目不得泄漏。
        assertEquals(0, map.size());
    }

    @Test
    void collectedKeyDoesNotResurfaceViaProbe() {
        final WeakKeyMap<Object, String> map = new WeakKeyMap<>();
        final WeakReference<Object> weak = new WeakReference<>(putAndReturnKey(map, "value"));
        final Object live = new Object();
        map.put(live, "live");

        forceGcUntil(map, weak, 1);
        assertNull(weak.get());

        // get 不触发 expunge 也能正确失配（死条目探测跳过），且不影响其他条目。
        assertEquals("live", map.get(live));
        assertEquals(1, map.size());
    }

    /** 生成键 → 入表 → 仅经弱引用返回（方法返回后键仅被表内弱引用持有）。 */
    private static Object putAndReturnKey(final WeakKeyMap<Object, String> map, final String value) {
        final Object key = new Object();
        map.put(key, value);
        return key;
    }

    /**
     * 分配压力 + System.gc 循环等待（参考 CellPoolTest 风格）：弱引用清空后，
     * 条目经引用队列在后续 size（expunge）摘除——清空与入队之间存在 GC 时序差，
     * 故以「弱引用清空 && 存活条目数收敛到 expectedLive」为循环终态，上限内
     * 强制回收与清理。任何一次失败都会在循环结束后由断言暴露。
     */
    private static void forceGcUntil(final WeakKeyMap<Object, String> map,
                                     final WeakReference<?> weak,
                                     final int expectedLive) {
        for (int i = 0; i < 100; i++) {
            if (weak.get() == null && map.size() == expectedLive) {
                return;
            }
            byte[] garbage = new byte[1 << 20];
            System.gc();
        }
        assertNull(weak.get(), "GC 循环后弱引用应被清空（回收语义验证的前置条件）");
        assertEquals(expectedLive, map.size(), "GC 循环后条目数应收敛到 " + expectedLive);
    }
}
