package github.kasuminova.ssoptimizer.api.render;

/**
 * 将包含 GL 调用的任务同步派发到持有上下文的渲染线程，供其他功能模块重放渲染逻辑。
 * <p>
 * 非渲染线程调用会等待任务完成，不适用于逐帧高频任务。
 * 未注册 = 渲染线程派发不可用，调用方不得在无 GL 上下文的线程回退执行任务。
 */
public interface RenderFramePump {

    /**
     * 同步执行渲染任务并等待其完成。普通非渲染线程会先提交当前录制帧；渲染线程直接执行，
     * 辅助原生 GL 线程可按底层队列规则内联执行。任务或调度失败以运行时异常向调用方报告。
     *
     * @param task 需要持有 GL 上下文执行的渲染逻辑
     */
    void runOnRenderThread(Runnable task);
}
