package github.kasuminova.ssoptimizer.api.render;

/** 线程分离模式下的存读档背景绘制。该模式启用时必须注册此接口。 */
public interface SaveOverlayFrames {
    /** 任意线程可调用；仅游戏主录制线程返回 true。 */
    boolean isProducer();

    /**
     * 主录制线程在绘制进度框之前调用。连续的进度帧复用最近一次普通游戏帧的背景。
     * 调用顺序与后续的绘制命令一致。此方法不交换窗口缓冲。
     * @param dialog 当前绘制的非空存读档弹窗
     */
    void restoreBackground(Object dialog);
}
