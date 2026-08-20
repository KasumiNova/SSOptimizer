package github.kasuminova.ssoptimizer.api.automation;

/**
 * 帧捕获钩子：Display 帧交换（update）后的调试帧捕获回调。
 * <p>
 * 动机：render 域的 bridge Display 需要在每次帧交换后通知 automation 域的
 * 帧捕获（基准截图/调试帧 dump），但 render 不允许反向依赖 automation——
 * 跨域行为调用经本接口。
 * <p>
 * 实现由 automation 域提供（DebugFrameCapture），在 coremod 装配期经
 * {@code ServiceRegistry} 注册；语义上允许缺省（未注册=不捕获），
 * 调用点经 {@code ServiceRegistry.getOrNull} 显式判空。
 */
public interface FrameCaptureHook {

    /**
     * Display.update 完成一次帧交换后回调（渲染线程上同步调用，实现必须轻量：
     * 仅做状态标记/异步派发，不得在此做阻塞 IO）。
     */
    void onDisplayUpdate();
}
