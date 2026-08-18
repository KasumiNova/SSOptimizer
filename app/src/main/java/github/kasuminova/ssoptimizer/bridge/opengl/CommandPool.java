package github.kasuminova.ssoptimizer.bridge.opengl;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Supplier;

/**
 * 命令对象池：跨线程复用 GL 命令对象（录制线程借出、渲染线程执行完归还）。
 * <p>
 * 动机：draw 命令与顶点流回放命令每帧数千次创建/回收，池化把稳态分配压到零。
 * 无锁队列保证借出（主线程/aux 生产者）与归还（渲染线程）互不阻塞，模式同
 * {@link github.kasuminova.ssoptimizer.common.render.queue.BufferSnapshotPoolImpl}。
 * <p>
 * 安全性约束：命令对象只有在「执行完成」后才归还（各命令 execute 的 finally）；
 * 悬挂续跑的 ContinuationTask 只持有悬挂点起未执行的命令引用，已执行并归还
 * 池的对象不会被再次执行。帧失败丢弃的未执行命令直接交 GC，不入池。
 *
 * @param <T> 池化对象类型
 */
final class CommandPool<T> {
    private final ConcurrentLinkedDeque<T> idle = new ConcurrentLinkedDeque<>();
    private final Supplier<T> factory;

    /**
     * @param factory 池空时的对象构造器
     */
    CommandPool(Supplier<T> factory) {
        this.factory = factory;
    }

    /** 借出一个对象（池空时新建）。 */
    T acquire() {
        T item = idle.poll();
        return item != null ? item : factory.get();
    }

    /** 归还对象（调用方不得再持有引用）。 */
    void release(T item) {
        idle.offer(item);
    }

    /** 测试用：池内空闲对象数。 */
    int idleCount() {
        return idle.size();
    }
}
