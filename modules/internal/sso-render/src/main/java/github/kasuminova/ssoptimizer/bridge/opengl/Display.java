package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.api.automation.FrameCaptureHook;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.DisplayMode;
import org.lwjgl.opengl.PixelFormat;

import java.nio.ByteBuffer;

/**
 * org.lwjgl.opengl.Display 的 bridge 镜像。
 * <p>
 * 动机同 {@link GL11}：ASM 重定向把游戏/模组字节码中
 * {@code org/lwjgl/opengl/Display} 的静态调用改写到本类。通道决策：
 * <ul>
 *   <li>{@link #update()} 是唯一携带 GL 语义的帧尾调用：入队一条真实
 *       {@code Display.update()}（交换缓冲，必须在持有上下文的渲染线程执行），
 *       随后 {@code swapFramesAndSync()} 完成帧提交与一帧重叠同步；</li>
 *   <li>{@link #create()}/{@link #setDisplayMode}/{@link #setFullscreen}/
 *       {@link #makeCurrent}/{@link #destroy}/{@link #setIcon}：窗口/上下文级
 *       变更，走阻塞通道在渲染线程执行（受检异常原样透传）。makeCurrent 在渲染
 *       线程上执行是幂等操作——上下文本来就归渲染线程持有，主线程调用只是
 *       「确认上下文可用」的语义占位，主线程自身从头到尾没有 GL context；</li>
 *   <li>{@link #setVSyncEnabled}/{@link #setTitle}/{@link #setLocation}：窗口
 *       属性变更无返回值依赖，按普通命令入队（X11 调用统一收口到渲染线程，
 *       避免跨线程操作同一 display connection）；</li>
 *   <li>{@link #getAvailableDisplayModes()}/{@link #getDesktopDisplayMode()}：
 *       实时查询 X11，走阻塞取值通道（同样为单线程化 X11 访问）；</li>
 *   <li>{@link #processMessages()}/{@link #isCloseRequested()}/{@link #isActive()}/
 *       {@link #isVisible()}/{@link #isFullscreen()}/{@link #isCreated()}/
 *       {@link #getDisplayMode()}/{@link #getWidth()}/{@link #getHeight()}/
 *       {@link #sync(int)}：读 LWJGL 缓存字段或纯 CPU 行为，无 GL/X11 依赖，
 *       直通真实 Display（注释保留：若后续把事件泵也迁到渲染线程，这里改为
 *       状态仿真或帧同步点读取）。</li>
 * </ul>
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
     * 在渲染线程创建 Display（含 GL 上下文），调用方阻塞至创建完成。
     * 结构对齐 FR：主线程全程无 context，创建语义经队列的阻塞通道落实。
     *
     * @throws LWJGLException 渲染线程上的真实 {@code Display.create()} 失败
     */
    public static void create() throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(org.lwjgl.opengl.Display::create);
        // 新上下文建立：录制侧簿记复位，capabilities 缓存随旧上下文一并失效
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /**
     * 携带像素格式参数的创建重载（游戏实际入口：{@code create(PixelFormat)}）。
     * 参数在主线程捕获（{@link PixelFormat} 是纯数据对象），真实创建在渲染线程执行。
     *
     * @param pixelFormat 请求的像素格式
     * @throws LWJGLException 渲染线程上的真实 {@code Display.create(PixelFormat)} 失败
     */
    public static void create(PixelFormat pixelFormat) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.create(pixelFormat));
        // 新上下文建立：录制侧簿记复位，capabilities 缓存随旧上下文一并失效
        BridgeSupport.onContextRecreated();
        GLContext.invalidateCapabilities();
    }

    /**
     * 帧尾：入队真实 {@code Display.update()}（渲染线程交换缓冲），
     * 然后提交当前帧并只等待上一帧完成（一帧流水线重叠）。swap 收口处
     * 同时刷新主录制线程的帧上下文缓存（见 {@link BridgeSupport#swapFramesAndSync()}）。
     * 命令体在渲染线程执行时先做 VBO id stash 低水位补货（
     * {@link BridgeSupport#refillBufferIdStashIfLow()}）——下一帧录制开始前
     * stash 已就位，主线程 glGenBuffers 不再阻塞（v45c/v47 getInternal 热点）。
     */
    /**
     * 调试帧抓取：渲染线程在 swap 前抓取 back buffer 写 PNG（主菜单/非战斗场景的
     * 渲染取证手段，bench 的截图机制只覆盖任务内）。逻辑见
     * automation 域的 DebugFrameCapture（经 {@link FrameCaptureHook} 钩子访问，
     * 支持 frame 单帧 / frames 多帧采样）。
     */

    /** 触发帧捕获钩子（automation 域注册；未注册=不捕获，属可省略显式判空）。 */
    private static void fireFrameCaptureHook() {
        final FrameCaptureHook hook = ServiceRegistry.getOrNull(FrameCaptureHook.class);
        if (hook != null) {
            hook.onDisplayUpdate();
        }
    }

    public static void update() {
        RenderQueue q = BridgeSupport.queue();
        q.submit(() -> {
            BridgeSupport.refillBufferIdStashIfLow();
            fireFrameCaptureHook();
            org.lwjgl.opengl.Display.update();
        });
        BridgeSupport.swapFramesAndSync();
    }

    /**
     * 携带事件泵开关的帧尾重载（战斗主循环的实际调用形态）。
     * 事件泵随 update 一起收口到渲染线程，X11 访问保持单线程化。
     *
     * @param processMessages 是否在交换缓冲前泵送窗口事件
     */
    public static void update(boolean processMessages) {
        RenderQueue q = BridgeSupport.queue();
        q.submit(() -> {
            BridgeSupport.refillBufferIdStashIfLow();
            fireFrameCaptureHook();
            org.lwjgl.opengl.Display.update(processMessages);
        });
        BridgeSupport.swapFramesAndSync();
    }

    /** 直通：事件泵无 GL 依赖（后续若迁移事件泵，此处改帧同步点读取）。 */
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
     * 折叠模型下的 Display drawable：返回进程级单例登记对象（{@link DisplayDrawable}），
     * 不产生 GL 命令。供 BoxUtil 类模组拿去包 {@link SharedDrawable} 的调用形态
     * 正常走通；上下文语义见 {@link SharedDrawable} 类 javadoc。
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
        // 显示模式切换重建 GL 上下文：录制侧状态簿记与 VBO id stash 全部复位
        BridgeSupport.onContextRecreated();
        // capabilities 缓存随旧上下文一并失效（见 GLContext.invalidateCapabilities）
        GLContext.invalidateCapabilities();
    }

    /**
     * 阻塞通道：全屏切换是窗口级变更，语义同 {@link #setDisplayMode(DisplayMode)}。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setFullscreen(boolean fullscreen) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setFullscreen(fullscreen));
        // 全屏切换重建 GL 上下文：录制侧状态簿记与 VBO id stash 全部复位
        BridgeSupport.onContextRecreated();
        // capabilities 缓存随旧上下文一并失效（见 GLContext.invalidateCapabilities）
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
     * 阻塞通道：确认上下文在渲染线程可用（幂等——上下文本就归渲染线程持有；
     * 主线程自身永远没有 context）。
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
     * 阻塞通道：图标设置是 X11 窗口操作，收口渲染线程；调用方阻塞期间
     * icons buffer 不被触碰，无需快照（启动期一次性调用，drain 可接受）。
     *
     * @return 真实 {@code Display.setIcon} 的返回值
     */
    public static int setIcon(ByteBuffer[] icons) {
        return BridgeSupport.blockingGetResource(() -> org.lwjgl.opengl.Display.setIcon(icons));
    }
}
