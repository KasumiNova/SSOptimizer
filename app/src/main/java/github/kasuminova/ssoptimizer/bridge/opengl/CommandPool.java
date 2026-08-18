package github.kasuminova.ssoptimizer.bridge.opengl;

import org.apache.log4j.Logger;
import org.jctools.queues.MpmcUnboundedXaddArrayQueue;

import java.util.function.Supplier;

/**
 * 命令对象池：跨线程复用 GL 命令对象（录制线程借出、渲染线程执行完归还）。
 * <p>
 * 动机：draw 命令与顶点流回放命令每帧数千次创建/回收，池化把稳态分配压到零。
 * 内部队列用 JCTools MPMC 无界 Xadd 数组队列：借出（主线程/aux 生产者，多消费者）
 * 与归还（渲染线程，单生产者）恰好构成 MPMC 语义，无锁互不阻塞；相对
 * {@link java.util.concurrent.ConcurrentLinkedDeque} 消除了链表节点的 CAS
 * 与缓存行抖动（v36 profile：{@code ConcurrentLinkedDeque.pollFirst} 1,704
 * 样本的主要来源）。无界队列保证归还永不丢弃（固定容量队列会在归还峰值期
 * 出现「归还即丢弃」，使池化退化回每帧分配，v44 实测回归）。
 * 模式同 {@link github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl}。
 * <p>
 * 安全性约束：命令对象只有在「执行完成」后才归还（各命令 execute 的 finally）；
 * 悬挂续跑的 ContinuationTask 只持有悬挂点起未执行的命令引用，已执行并归还
 * 池的对象不会被再次执行。帧失败丢弃的未执行命令直接交 GC，不入池。
 *
 * @param <T> 池化对象类型
 */
final class CommandPool<T> {
    /** 初始 chunk 容量（2 的幂）；队列按 chunk 链增长。 */
    private static final int INITIAL_CAPACITY = 1024;

    private final MpmcUnboundedXaddArrayQueue<T> idle;
    private final Supplier<T> factory;

    /**
     * @param factory 池空时的对象构造器
     */
    CommandPool(Supplier<T> factory) {
        this.idle = new MpmcUnboundedXaddArrayQueue<>(INITIAL_CAPACITY);
        this.factory = factory;
    }

    /** 借出一个对象（池空时新建）。 */
    T acquire() {
        T item = idle.poll();
        return item != null ? item : factory.get();
    }

    /** 归还对象（调用方不得再持有引用）；无界队列，不丢对象。 */
    void release(T item) {
        idle.offer(item);
    }

    /** 测试用：池内空闲对象数。 */
    int idleCount() {
        return idle.size();
    }
}
