package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.util.function.Supplier;

/**
 * 跨线程复用 GL 命令对象。录制线程经线程本地栈批量预借，渲染线程在执行完毕后
 * 归还全局 MPMC 队列；未执行完的命令不得归还。
 *
 * @param <T> 池化对象类型
 */
final class CommandPool<T> {
    private static final int LOCAL_BATCH = 32;
    private static final int GLOBAL_INITIAL_CAPACITY = 1024;

    private final MpmcUnboundedXaddArrayQueue<T> idle;
    private final Supplier<T> factory;
    private final ThreadLocal<LocalStack<T>> local =
            ThreadLocal.withInitial(() -> new LocalStack<>(LOCAL_BATCH));

    CommandPool(Supplier<T> factory) {
        this.idle = new MpmcUnboundedXaddArrayQueue<>(GLOBAL_INITIAL_CAPACITY);
        this.factory = factory;
    }

    T acquire() {
        LocalStack<T> stack = local.get();
        T item = stack.pop();
        if (item != null) {
            return item;
        }
        stack.refillFrom(idle);
        item = stack.pop();
        return item != null ? item : factory.get();
    }

    void release(T item) {
        idle.offer(item);
    }

    int idleCount() {
        return idle.size();
    }

    private static final class LocalStack<T> {
        private final Object[] items;
        private int size;

        LocalStack(int capacity) {
            this.items = new Object[capacity];
        }

        T pop() {
            if (size == 0) {
                return null;
            }
            @SuppressWarnings("unchecked")
            T item = (T) items[--size];
            items[size] = null;
            return item;
        }

        int refillFrom(MpmcUnboundedXaddArrayQueue<T> idle) {
            int filled = 0;
            while (size < items.length) {
                T pooled = idle.poll();
                if (pooled == null) {
                    break;
                }
                items[size++] = pooled;
                filled++;
            }
            return filled;
        }
    }
}
