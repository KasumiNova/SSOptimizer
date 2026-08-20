package github.kasuminova.ssoptimizer.common.render.queue;

/**
 * 帧悬挂控制信号：{@link WaitFenceCommand} 执行时 fence 尚未 signal 即抛出，
 * 由 {@link RenderQueueImpl} 捕获并把当前位置起的剩余命令打包为续跑任务 requeue。
 * <p>
 * 动机（BoxUtil 三方死锁的修复）：渲染线程无条件阻塞等 fence 会形成
 * 「main 等上一帧完成 → 渲染线程等 fence → BoxUtil 生产者等 main 到达 Phaser →
 * main 永远到不了」的环。改为非阻塞悬挂后本帧正常完成、main 被释放推进到
 * Phaser，fence 信号随后到达，续跑任务在后续调度回合完成余下命令。
 * <p>
 * 本异常是队列内部的控制流信号，业务代码不得捕获；单例且不填栈轨迹，
 * 悬挂恢复期的每次重试零分配。
 */
public final class SuspendFrameException extends RuntimeException {
    /** 全局唯一实例（控制流信号无载荷，复用避免恢复期逐毫秒分配）。 */
    static final SuspendFrameException INSTANCE = new SuspendFrameException();

    private SuspendFrameException() {
        super(null, null, false, false);
    }
}
