package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.opengl.ContextCapabilities;

/**
 * org.lwjgl.opengl.GLContext 的 bridge 镜像（能力探测入口）。
 * <p>
 * 动机同 {@link GL11}。{@link #getCapabilities()} 是启动期能力探测的高频入口
 * （GraphicsLib 按 capabilities 选择 FBO 路径等）：首次调用走阻塞通道在渲染线程
 * 取回真实 {@link ContextCapabilities} 并缓存，此后主线程直接读缓存——
 * capabilities 在上下文生命周期内不变，是 getter 仿真清单里「一次性取回并缓存」
 * 策略的落点（见 gl-call-inventory.md getter 特殊处理清单）。
 */
public final class GLContext {
    private static volatile ContextCapabilities capabilities;
    private static volatile boolean capabilitiesFetched;

    private GLContext() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载队列并清空 capabilities 缓存，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
        capabilities = null;
        capabilitiesFetched = false;
    }

    /**
     * 取回当前上下文的能力表。首次调用经阻塞通道在渲染线程执行真实
     * {@code GLContext.getCapabilities()}（要求 {@link Display#create()} 已完成）
     * 并缓存结果；后续调用零阻塞读缓存。
     *
     * @return 缓存的能力表（生命周期与上下文一致）
     */
    public static ContextCapabilities getCapabilities() {
        if (!capabilitiesFetched) {
            synchronized (GLContext.class) {
                if (!capabilitiesFetched) {
                    capabilities = BridgeSupport.blockingGet(org.lwjgl.opengl.GLContext::getCapabilities);
                    capabilitiesFetched = true;
                }
            }
        }
        return capabilities;
    }
}
