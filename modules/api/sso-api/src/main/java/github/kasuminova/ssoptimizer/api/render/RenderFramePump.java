package github.kasuminova.ssoptimizer.api.render;

/**
 * 渲染线程帧回放泵：把一段 GL 渲染逻辑送到渲染线程阻塞执行。
 * <p>
 * 动机：渲染线程分离模式下 GL 上下文只存在于渲染线程，非渲染域模块
 * （如 save 域的存档进度覆盖层）需要在渲染线程上重放原版 UI 帧，
 * 但不允许直接引用 bridge 实现类——跨域行为调用一律经本接口。
 * <p>
 * 实现由 render 域提供（包装 RenderThreadDispatch），
 * 在 coremod 装配期经 {@code ServiceRegistry} 注册；
 * 消费方仅在 {@code RenderThreadMode.isEnabled()} 时 {@code require} 本服务。
 */
public interface RenderFramePump {

    /**
     * 在渲染线程上阻塞执行给定渲染任务（当前线程等待执行完成）。
     *
     * @param task 需要在持有 GL 上下文的渲染线程上执行的渲染逻辑
     */
    void runOnRenderThread(Runnable task);
}
