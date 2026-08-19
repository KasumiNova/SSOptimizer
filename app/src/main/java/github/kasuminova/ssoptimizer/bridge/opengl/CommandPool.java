package github.kasuminova.ssoptimizer.bridge.opengl;

import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.util.function.Supplier;

/**
 * 命令对象池：跨线程复用 GL 命令对象（录制线程借出、渲染线程执行完归还）。
 * <p>
 * 动机：draw 命令与顶点流回放命令每帧数千次创建/回收，池化把稳态分配压到零。
 * <p>
 * <b>借出降频（v45c profile：{@code CommandPool.acquire} 904 + CAS 466 样本）</b>：
 * 每个生产者线程带一个本地预借栈（{@link #LOCAL_BATCH} 个槽）。{@link #acquire()}
 * 命中本地栈时仅一次数组操作（零 CAS 零队列访问）；栈空时一次从全局池批量
 * 预借补满（批量 poll 与分散 poll 的 CAS 总量守恒，但把主线程热路径的逐调用
 * 队列访问集中到低频补货点，且命中路径不再触碰跨线程队列）。预借的实例最终
 * 经命令消费后由渲染线程 {@link #release(Object)} 归还全局池——本地栈是「在途
 * 预借」，不会积压不回。
 * <p>
 * 归还：直接进全局池（渲染线程不在主线程 profile 内，无需本地缓存；直接
 * 归还也保持 {@link #idleCount()} 的「池内空闲」语义，测试与诊断不二义）。
 * <p>
 * 内部队列用 JCTools MPMC 无界 Xadd 数组队列：借出（主线程/aux 生产者，
 * 多消费者）与归还（渲染线程，单生产者）恰好构成 MPMC 语义，无锁互不阻塞；
 * 相对 {@link java.util.concurrent.ConcurrentLinkedDeque} 消除了链表节点的 CAS
 * 与缓存行抖动（v36 profile：{@code ConcurrentLinkedDeque.pollFirst} 1,704
 * 样本的主要来源）。无界队列保证归还永不丢弃（固定容量队列会在归还峰值期
 * 出现「归还即丢弃」，使池化退化回每帧分配，v44 实测回归）。
 * <p>
 * 安全性约束：命令对象只有在「执行完成」后才归还（各命令 execute 的 finally）；
 * 悬挂续跑的 ContinuationTask 只持有悬挂点起未执行的命令引用，已执行并归还
 * 池的对象不会被再次执行。帧失败丢弃的未执行命令直接交 GC，不入池。
 *
 * @param <T> 池化对象类型
 */
final class CommandPool<T> {
    /** 每线程本地预借栈容量（2 的幂无需，普通数组即可）。 */
    private static final int LOCAL_BATCH = 32;
    /** 初始 chunk 容量（2 的幂）；全局队列按 chunk 链增长。 */
    private static final int GLOBAL_INITIAL_CAPACITY = 1024;

    private final MpmcUnboundedXaddArrayQueue<T> idle;
    private final Supplier<T> factory;
    private final ThreadLocal<LocalStack<T>> local =
            ThreadLocal.withInitial(() -> new LocalStack<>(LOCAL_BATCH));

    /**
     * @param factory 池空时的对象构造器
     */
    CommandPool(Supplier<T> factory) {
        this.idle = new MpmcUnboundedXaddArrayQueue<>(GLOBAL_INITIAL_CAPACITY);
        this.factory = factory;
    }

    /** 借出一个对象（本地栈命中零队列访问；栈空时批量预借，仍空则新建）。 */
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

    /** 归还对象（调用方不得再持有引用）；直接进全局池，无界队列不丢对象。 */
    void release(T item) {
        idle.offer(item);
    }

    /** 测试用：全局池内空闲对象数（不含线程本地预借栈）。 */
    int idleCount() {
        return idle.size();
    }

    /** 每线程本地预借栈：数组 + 计数，仅本线程读写，无同步。 */
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

        /** 从全局池批量 poll 填充到栈满或池空；返回本次填充数。 */
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
