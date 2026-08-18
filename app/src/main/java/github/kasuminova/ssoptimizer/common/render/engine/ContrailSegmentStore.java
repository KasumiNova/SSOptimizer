package github.kasuminova.ssoptimizer.common.render.engine;

import java.util.AbstractList;
import java.util.Iterator;

/**
 * ContrailEngine 段容器的数组后备 {@link java.util.List} 实现，替换原版
 * {@code ContrailGroup.segments} 的 {@code LinkedList}（v49 profile：
 * LinkedList 迭代 2,473 样本，advance 46.3% + encodeGroup 11% 都迭代同一份
 * 段列表）。链表节点逐帧分配 + 节点指针逐段寻址（缓存行不连续）是迭代热点的
 * 来源；数组后备把迭代折叠为连续内存顺序访问。
 * <p>
 * <b>语义等价</b>：原版对 {@code segments} 只使用 List API（add 尾插、
 * get(i)、size、isEmpty、remove(0) 头删、iterator），本实现以相同语义覆盖
 * 全部接口——尾插保持增序，头删保持元素次序（左移，n 小开销可忽略），迭代
 * 按索引顺序（段 0 = 最旧，段 size-1 = 最新，与原 LinkedList 一致）。游戏
 * 方法（advance 外的 addSegment/extendTrail/removeExpiredSegment）无需改写
 * 即可在此容器上工作。
 * <p>
 * 并发边界：advance 与 render 均在游戏主线程调用（ContrailEngine 的更新/
 * 渲染入口无跨线程共享），本容器不做同步。
 */
public final class ContrailSegmentStore extends AbstractList<Object> {
    /** 初始容量（2 的幂；典型尾迹段数在数十~数百，8 起步足够，超出按倍增长）。 */
    private static final int INITIAL_CAPACITY = 8;

    private Object[] elements;
    private int size;

    public ContrailSegmentStore() {
        this(INITIAL_CAPACITY);
    }

    public ContrailSegmentStore(int initialCapacity) {
        if (initialCapacity < 1) {
            throw new IllegalArgumentException("initial capacity must be >= 1: " + initialCapacity);
        }
        this.elements = new Object[initialCapacity];
    }

    @Override
    public Object get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " out of [0, " + size + ")");
        }
        return elements[index];
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public boolean add(Object element) {
        if (size == elements.length) {
            grow();
        }
        elements[size++] = element;
        return true;
    }

    /** 头删（ContrailEngine 死亡段移除的唯一形态，原版 remove(0) 的数组后备实现）。 */
    @Override
    public Object remove(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " out of [0, " + size + ")");
        }
        Object removed = elements[index];
        int moved = size - index - 1;
        if (moved > 0) {
            System.arraycopy(elements, index + 1, elements, index, moved);
        }
        elements[--size] = null;
        return removed;
    }

    /** 容量不足时按 1.5 倍增长（与 ArrayList 同量级摊还）。 */
    private void grow() {
        int newCapacity = elements.length + (elements.length >> 1);
        Object[] grown = new Object[newCapacity];
        System.arraycopy(elements, 0, grown, 0, size);
        elements = grown;
    }

    @Override
    public Iterator<Object> iterator() {
        // AbstractList.Itr 为数组索引迭代（与原版 LinkedList 的节点迭代语义一致：
        // 段 0 起按序），生产路径的 advance/render 均改用索引访问本容器，
        // 迭代器仅满足 List 契约。
        return super.iterator();
    }
}
