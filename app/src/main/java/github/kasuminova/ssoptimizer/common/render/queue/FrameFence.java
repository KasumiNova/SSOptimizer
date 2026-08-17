package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 一次 glFenceSync 语义的队列内表示。
 * <p>
 * 动机：BoxUtil 类模组用 glFenceSync/glWaitSync 做跨上下文的 GPU 命令流可见性协调。
 * 录制化之后，glWaitSync 可能被记录到对应 glFenceSync 之前执行（跨线程录制顺序
 * 不保证），若 fence 仅按队列顺序到位会造成渲染线程死锁。因此 fence 是一个可以
 * 被任意线程完成的会合点：信号既可来自渲染线程执行到
 * {@link SignalFenceCommand}（glFenceSync 录制在渲染流上），也可来自 CPU 侧的
 * 生产者线程直接 {@link #signal()}（对应 BoxUtil 的 Phaser/fence CPU 协调）。
 */
public interface FrameFence {
    /**
     * 标记 fence 已通过。幂等：多次调用与一次调用等价。
     */
    void signal();

    /**
     * 阻塞至 fence 被 {@link #signal()}。供 {@link WaitFenceCommand} 在渲染线程使用。
     *
     * @throws InterruptedException 等待线程被中断
     */
    void await() throws InterruptedException;

    /**
     * @return fence 是否已被 signal（诊断/测试用）
     */
    boolean isSignaled();
}
