package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.automation.FrameCaptureHook;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.PixelFormat;

import java.nio.ByteBuffer;

/**
 * LWJGL Display 的桥接入口。窗口和上下文操作收口到渲染线程，缓存状态查询与纯 CPU 操作
 * 保留在调用线程执行。
 */
public final class Display {
    private Display() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        BridgeSupport.install(renderQueue);
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        BridgeSupport.uninstall();
    }

    /**
     * 在渲染线程创建 Display 与 GL 上下文，调用方阻塞至创建完成。
     *
     * @throws LWJGLException 渲染线程上的真实 {@code Display.create()} 失败
     */
    public static void create() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create();
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /**
     * 像素格式由调用线程捕获，真实创建在渲染线程执行。
     *
     * @param pixelFormat 请求的像素格式
     * @throws LWJGLException 渲染线程上的真实 {@code Display.create(PixelFormat)} 失败
     */
    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    private static void fireFrameCaptureHook() {
        final FrameCaptureHook hook = ServiceRegistry.getOrNull(FrameCaptureHook.class);
        if (hook != null) {
            hook.onDisplayUpdate();
        }
    }

    public static void update() {
        RenderQueue q = BridgeSupport.queue();
        // update 直交通道必须先落帧，避免挂起顶点流在 swapBuffers 后执行
        BridgeSupport.flushVertexStream();
        q.submit(() -> {
            BridgeSupport.refillBufferIdStashIfLow();
            fireFrameCaptureHook();
            org.lwjgl.opengl.Display.update();
        });
        BridgeSupport.swapFramesAndSync();
    }

    /**
     * 事件泵随 update 在渲染线程执行，X11 访问保持单线程化。
     *
     * @param processMessages 是否在交换缓冲前泵送窗口事件
     */
    public static void update(boolean processMessages) {
        RenderQueue q = BridgeSupport.queue();
        BridgeSupport.flushVertexStream();
        q.submit(() -> {
            BridgeSupport.refillBufferIdStashIfLow();
            fireFrameCaptureHook();
            org.lwjgl.opengl.Display.update(processMessages);
        });
        BridgeSupport.swapFramesAndSync();
    }

    /** 事件泵无 GL 依赖，保留在调用线程执行。 */
    public static void processMessages() {
        org.lwjgl.opengl.Display.processMessages();
    }

    /** 直通：窗口关闭标志无 GL 依赖。 */
    public static boolean isCloseRequested() {
        return org.lwjgl.opengl.Display.isCloseRequested();
    }

    /** 直通：窗口激活标志无 GL 依赖。 */
    public static boolean isActive() {
        return org.lwjgl.opengl.Display.isActive();
    }

    /** 直通：窗口可见标志读 LWJGL 缓存字段，无 GL 依赖。 */
    public static boolean isVisible() {
        return org.lwjgl.opengl.Display.isVisible();
    }

    /** 直通：Display 创建标志读 LWJGL 缓存字段，无 GL 依赖。 */
    public static boolean isCreated() {
        return org.lwjgl.opengl.Display.isCreated();
    }

    /** 直通：dirty 标志读 LWJGL 缓存字段，无 GL 依赖。 */
    public static boolean isDirty() {
        return org.lwjgl.opengl.Display.isDirty();
    }

    /** 直通：像素缩放因子读 LWJGL 缓存字段（游戏定制 LWJGL 扩展），无 GL 依赖。 */
    public static float getPixelScaleFactor() {
        return org.lwjgl.opengl.Display.getPixelScaleFactor();
    }

    /**
     * 返回 Display 本体 drawable 的进程级单例，不产生 GL 命令。
     *
     * @return Display 的 bridge drawable 单例
     */
    public static Drawable getDrawable() {
        return DisplayDrawable.INSTANCE;
    }

    /** 直通：全屏标志读 LWJGL 缓存字段，无 GL 依赖。 */
    public static boolean isFullscreen() {
        return org.lwjgl.opengl.Display.isFullscreen();
    }

    /** 直通：当前显示模式读 LWJGL 缓存字段，无 GL 依赖。 */
    public static DisplayMode getDisplayMode() {
        return org.lwjgl.opengl.Display.getDisplayMode();
    }

    /** 直通：窗口尺寸读的是 LWJGL 缓存字段，无 GL 依赖。 */
    public static int getWidth() {
        return org.lwjgl.opengl.Display.getWidth();
    }

    /** 直通：同 {@link #getWidth()}。 */
    public static int getHeight() {
        return org.lwjgl.opengl.Display.getHeight();
    }

    /**
     * 直通：帧率限制是纯 CPU 计时（sleep+自旋），不触碰 GL/X11，
     * 留在调用线程执行。
     */
    public static void sync(int fps) {
        org.lwjgl.opengl.Display.sync(fps);
    }

    /**
     * 阻塞通道：实时枚举 X11 显示模式，收口到渲染线程执行以单线程化 X11 访问。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static DisplayMode[] getAvailableDisplayModes() throws LWJGLException {
        return BridgeSupport.blockingGetLwjgl(org.lwjgl.opengl.Display::getAvailableDisplayModes);
    }

    /** 阻塞通道：桌面显示模式是实时 X11 查询，语义同 {@link #getAvailableDisplayModes()}。 */
    public static DisplayMode getDesktopDisplayMode() {
        return BridgeSupport.blockingGet(org.lwjgl.opengl.Display::getDesktopDisplayMode);
    }

    /**
     * 阻塞通道：显示模式切换是窗口级变更，在渲染线程执行并阻塞至完成。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setDisplayMode(DisplayMode mode) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setDisplayMode(mode));
        // 上下文重建后，录制状态、VBO id stash 与 capabilities 缓存一并失效
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /**
     * 阻塞通道：全屏切换是窗口级变更，语义同 {@link #setDisplayMode(DisplayMode)}。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setFullscreen(boolean fullscreen) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setFullscreen(fullscreen));
        // 上下文重建后，录制状态、VBO id stash 与 capabilities 缓存一并失效
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /** 窗口标题变更：无返回值依赖，按普通命令入队（X11 收口渲染线程）。 */
    public static void setTitle(String title) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setTitle(title));
    }

    /** 窗口位置变更：无返回值依赖，按普通命令入队（X11 收口渲染线程）。 */
    public static void setLocation(int x, int y) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setLocation(x, y));
    }

    /** vsync 开关作用于交换缓冲路径，入队到持有上下文的渲染线程执行。 */
    public static void setVSyncEnabled(boolean vsync) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setVSyncEnabled(vsync));
    }

    /**
     * 确认上下文在渲染线程可用；调用线程自身不持有真实上下文。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void makeCurrent() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(org.lwjgl.opengl.Display::makeCurrent);
    }

    /** 阻塞通道：销毁窗口/上下文必须在渲染线程完成后再返回调用方（关停顺序保证）。 */
    public static void destroy() {
        BridgeSupport.blockingWait(org.lwjgl.opengl.Display::destroy);
    }

    /**
     * X11 图标设置在渲染线程执行，调用方阻塞期间 icons buffer 不被触碰。
     *
     * @return 真实 {@code Display.setIcon} 的返回值
     */
    public static int setIcon(ByteBuffer[] icons) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.Display.setIcon(icons));
    }

    /**
     * 仅接受 {@link #getDrawable()} 返回的 Display 本体单例，其他 drawable 显式拒绝。
     *
     * @param drawable   Display 本体 drawable
     * @throws LWJGLException 渲染线程上的真实创建失败
     */
    public static void create(PixelFormat pixelFormat, Drawable drawable) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    /**
     * 携带上下文属性的创建重载：{@link org.lwjgl.opengl.ContextAttribs} 是纯数据对象，
     * 主线程捕获后真实创建在渲染线程执行。
     *
     * @throws LWJGLException 渲染线程上的真实创建失败
     */
    public static void create(PixelFormat pixelFormat,
                              org.lwjgl.opengl.ContextAttribs attribs) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat, attribs);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /** drawable + 上下文属性的创建重载，drawable 约束同 {@link #create(PixelFormat, Drawable)}。 */
    public static void create(PixelFormat pixelFormat, Drawable drawable,
                              org.lwjgl.opengl.ContextAttribs attribs) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat, attribs);
    }

    /** PixelFormatLWJGL 接口形态的创建重载，语义同 {@link #create(PixelFormat)}。 */
    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat,
                              org.lwjgl.opengles.ContextAttribs attribs) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> {
            org.lwjgl.opengl.Display.create(pixelFormat, attribs);
            BridgeSupport.onRenderThreadGlContextReady();
        });
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /** drawable 约束同 {@link #create(PixelFormat, Drawable)}。 */
    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat,
                              Drawable drawable) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    public static void create(org.lwjgl.opengl.PixelFormatLWJGL pixelFormat, Drawable drawable,
                              org.lwjgl.opengles.ContextAttribs attribs) throws LWJGLException {
        requireDisplayDrawable(drawable);
        create(pixelFormat);
    }

    /** create 重载的 drawable 入参校验：折叠模型下只接受 Display 本体 drawable 单例。 */
    private static void requireDisplayDrawable(Drawable drawable) {
        if (drawable != DisplayDrawable.INSTANCE) {
            throw new IllegalArgumentException(
                    "[SSOptimizer] Display.create 的 drawable 参数只支持 Display.getDrawable()"
                            + " 的本体单例（折叠模型下不存在可创建 Display 的自定义 drawable），实际: "
                            + drawable);
        }
    }

    /**
     * 阻塞通道：显示模式 + 全屏一次性切换，语义同 {@link #setDisplayMode(DisplayMode)}。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setDisplayModeAndFullscreen(DisplayMode mode) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setDisplayModeAndFullscreen(mode));
        // 显示模式切换重建 GL 上下文：录制侧状态簿记与 VBO id stash 全部复位
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /**
     * 阻塞通道：gamma/亮度/对比度设置是窗口级 X11 变更，收口渲染线程执行
     * （不重建 GL 上下文，无需复位簿记）。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setDisplayConfiguration(float gamma, float brightness,
                                               float contrast) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(
                () -> org.lwjgl.opengl.Display.setDisplayConfiguration(gamma, brightness, contrast));
    }

    /**
     * 阻塞通道：交换缓冲是帧尾 GL 语义调用，必须在持有上下文的渲染线程执行；
     * drain-first 保证此前录制的全部命令先于交换执行（游戏主循环走
     * {@link #update()}，本入口服务直接调 swapBuffers 的模组）。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void swapBuffers() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(org.lwjgl.opengl.Display::swapBuffers);
    }

    /**
     * 阻塞通道：解除上下文关联在渲染线程执行是幂等确认（主线程自身永远没有
     * context），语义同 {@link #makeCurrent()}。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void releaseContext() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(org.lwjgl.opengl.Display::releaseContext);
    }

    /**
     * 阻塞通道：真实上下文恒在渲染线程上 current，查询收口渲染线程取真值。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static boolean isCurrent() throws LWJGLException {
        return BridgeSupport.blockingGetLwjgl(org.lwjgl.opengl.Display::isCurrent);
    }

    /**
     * 阻塞通道：嵌入 AWT 父容器是窗口级变更，收口渲染线程执行。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setParent(java.awt.Canvas parent) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setParent(parent));
    }

    /** 直通：窗口标题读 LWJGL 缓存字段，无 GL/X11 依赖。 */
    public static String getTitle() {
        return org.lwjgl.opengl.Display.getTitle();
    }

    /** 直通：AWT 父容器读 LWJGL 缓存字段，无 GL/X11 依赖。 */
    public static java.awt.Canvas getParent() {
        return org.lwjgl.opengl.Display.getParent();
    }

    /** 窗口背景色设置：无返回值依赖，按普通命令入队（X11 收口渲染线程）。 */
    public static void setInitialBackground(float red, float green, float blue) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setInitialBackground(red, green, blue));
    }

    /** swap 间隔作用于交换缓冲路径，入队到持有上下文的渲染线程执行。 */
    public static void setSwapInterval(int interval) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setSwapInterval(interval));
    }

    /** 可缩放标志变更：无返回值依赖，按普通命令入队（X11 收口渲染线程）。 */
    public static void setResizable(boolean resizable) {
        BridgeSupport.enqueue(() -> org.lwjgl.opengl.Display.setResizable(resizable));
    }

    /** 直通：可缩放标志读 LWJGL 缓存字段，无 GL/X11 依赖。 */
    public static boolean isResizable() {
        return org.lwjgl.opengl.Display.isResizable();
    }

    /** 直通：尺寸变化标志读 LWJGL 缓存字段，无 GL/X11 依赖。 */
    public static boolean wasResized() {
        return org.lwjgl.opengl.Display.wasResized();
    }

    /** 直通：窗口坐标读 LWJGL 缓存字段，无 GL/X11 依赖。 */
    public static int getX() {
        return org.lwjgl.opengl.Display.getX();
    }

    /** 直通：同 {@link #getX()}。 */
    public static int getY() {
        return org.lwjgl.opengl.Display.getY();
    }

    /** 直通：显卡适配器名是启动期缓存的字符串，无 GL/X11 依赖。 */
    public static String getAdapter() {
        return org.lwjgl.opengl.Display.getAdapter();
    }

    /** 直通：驱动版本串同 {@link #getAdapter()}。 */
    public static String getVersion() {
        return org.lwjgl.opengl.Display.getVersion();
    }
}
