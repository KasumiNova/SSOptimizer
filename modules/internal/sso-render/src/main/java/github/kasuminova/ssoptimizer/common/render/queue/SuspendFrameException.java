package github.kasuminova.ssoptimizer.common.render.queue;

/** 队列内部的帧悬挂控制信号；单例不填栈轨迹，业务代码不得捕获。 */
public final class SuspendFrameException extends RuntimeException {
    public static final SuspendFrameException INSTANCE = new SuspendFrameException();

    private SuspendFrameException() {
        super(null, null, false, false);
    }
}
