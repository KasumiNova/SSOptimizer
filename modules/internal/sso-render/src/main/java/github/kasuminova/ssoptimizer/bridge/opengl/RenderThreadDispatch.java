package github.kasuminova.ssoptimizer.bridge.opengl;

/** 非渲染线程调用时先提交当前录制帧再阻塞执行；任务异常传播给调用方，不得用于逐帧高频路径。 */
public final class RenderThreadDispatch {
    private RenderThreadDispatch() {
    }

    public static void runBlocking(final Runnable task) {
        BridgeSupport.blockingWaitResource(task);
    }
}
