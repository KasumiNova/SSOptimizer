package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 一次 glFenceSync 语义的队列内表示。
 * <p>
 * 动机：BoxUtil 类模组用 glFenceSync/glWaitSync 做跨上下文的 GPU 命令流可见性协调。
 * 录制化之后 fence 是一个可以被任意线程完成的会合点：信号既可来自渲染线程执行到
 * {@link SignalFenceCommand}（glFenceSync 录制在渲染流上），也可来自 CPU 侧的
 * 生产者线程直接 {@link #signal()}（对应 BoxUtil 的 Phaser/fence CPU 协调）。
 * <p>
 * 等待侧（{@link WaitFenceCommand}）不阻塞：fence 未 signal 时帧执行悬挂续跑
 * （见 {@link SuspendFrameException}），因此本接口只暴露 signal 与状态查询。
 */
public interface FrameFence {
    /**
     * 标记 fence 已通过。幂等：多次调用与一次调用等价。
     */
    void signal();

    /**
     * @return fence 是否已被 signal（{@link WaitFenceCommand} 的放行判据，兼诊断/测试用）
     */
    boolean isSignaled();
}
