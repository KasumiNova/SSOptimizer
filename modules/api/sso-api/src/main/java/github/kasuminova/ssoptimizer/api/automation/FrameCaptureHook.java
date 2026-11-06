package github.kasuminova.ssoptimizer.api.automation;

/**
 * 在帧交换前向自动化模块提供同步捕获入口，使渲染模块无需依赖帧捕获实现。
 * <p>
 * 回调在渲染线程执行，调用方会等待其完成；实现可读取帧缓冲并写出捕获文件，捕获失败不得中断后续帧。
 * 未注册 = 不捕获帧，调用方跳过回调。
 */
public interface FrameCaptureHook {

    /**
     * 在一次 Display 帧交换前同步回调。
     * 仅捕获配置命中时产生文件与日志副作用，调用线程会等待这些操作完成。
     */
    void onDisplayUpdate();
}
