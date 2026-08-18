package github.kasuminova.ssoptimizer.common.render.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ContrailSegmentStore} 的容器语义验证：数组后备 List 相对原版
 * LinkedList 的等价契约（尾插保序、头删保序、索引访问、迭代顺序、容量增长）。
 * <p>
 * 语义等价论证：原版对段容器只使用 List API（add 尾插、get(i)、size、
 * isEmpty、remove(0)、iterator），本测试逐条验证这些操作的可观察行为与
 * 顺序语义（段 0 = 最旧，段 size-1 = 最新）。
 */
class ContrailSegmentStoreTest {

    @Test
    void appendsPreserveInsertionOrder() {
        ContrailSegmentStore store = new ContrailSegmentStore();
        for (int i = 0; i < 5; i++) {
            store.add(segment(i));
        }
        assertEquals(5, store.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(segment(i), store.get(i), "索引 i 返回第 i 个追加的元素");
        }
        assertFalse(store.isEmpty());
    }

    @Test
    void headRemovalPreservesOrderOfRemainingElements() {
        ContrailSegmentStore store = new ContrailSegmentStore();
        for (int i = 0; i < 5; i++) {
            store.add(segment(i));
        }

        assertEquals(segment(0), store.remove(0), "头删返回最旧段（原版 remove(0)）");
        assertEquals(4, store.size());
        assertEquals(segment(1), store.get(0), "头删后元素次序不变");

        assertEquals(segment(1), store.remove(0), "再次头删（原版逐帧死亡移除形态）");
        assertEquals(3, store.size());
        assertEquals(segment(2), store.get(0));
    }

    @Test
    void growthBeyondInitialCapacityKeepsOrderAndContent() {
        ContrailSegmentStore store = new ContrailSegmentStore(); // 初始容量 8
        for (int i = 0; i < 40; i++) {
            store.add(segment(i));
        }
        assertEquals(40, store.size());
        for (int i = 0; i < 40; i++) {
            assertEquals(segment(i), store.get(i));
        }
        // 头删 + 追加混合（原版死亡移除 + 新段追加的稳态形态）
        for (int i = 0; i < 10; i++) {
            store.remove(0);
            store.add(segment(100 + i));
        }
        assertEquals(40, store.size());
        assertEquals(segment(10), store.get(0));
        assertEquals(segment(109), store.get(39));
    }

    @Test
    void iteratorYieldsElementsInIndexOrder() {
        ContrailSegmentStore store = new ContrailSegmentStore();
        for (int i = 0; i < 6; i++) {
            store.add(segment(i));
        }
        List<Object> visited = new ArrayList<>();
        Iterator<Object> it = store.iterator();
        while (it.hasNext()) {
            visited.add(it.next());
        }
        assertEquals(List.of(segment(0), segment(1), segment(2), segment(3), segment(4), segment(5)), visited);
    }

    @Test
    void emptyStoreBehavesLikeEmptyList() {
        ContrailSegmentStore store = new ContrailSegmentStore();
        assertTrue(store.isEmpty());
        assertEquals(0, store.size());
        assertFalse(store.iterator().hasNext());
        assertThrows(IndexOutOfBoundsException.class, () -> store.get(0));
        assertThrows(IndexOutOfBoundsException.class, () -> store.remove(0));
    }

    private static String segment(int id) {
        return "seg-" + id;
    }
}
