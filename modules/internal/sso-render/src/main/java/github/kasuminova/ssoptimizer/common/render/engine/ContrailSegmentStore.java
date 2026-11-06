package github.kasuminova.ssoptimizer.common.render.engine;

import java.util.AbstractList;
import java.util.Iterator;

/** 尾迹段的数组后备列表，仅在游戏主线程使用。 */
public final class ContrailSegmentStore extends AbstractList<Object> {
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

    private void grow() {
        int newCapacity = elements.length + (elements.length >> 1);
        Object[] grown = new Object[newCapacity];
        System.arraycopy(elements, 0, grown, 0, size);
        elements = grown;
    }

    @Override
    public Iterator<Object> iterator() {
        return super.iterator();
    }
}
