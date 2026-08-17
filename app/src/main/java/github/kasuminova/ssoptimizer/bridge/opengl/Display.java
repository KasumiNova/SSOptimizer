package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.DisplayMode;

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
    }

    /**
     * 帧尾：入队真实 {@code Display.update()}（渲染线程交换缓冲），
     * 然后提交当前帧并只等待上一帧完成（一帧流水线重叠）。
     */
    public static void update() {
        RenderQueue q = BridgeSupport.queue();
        q.submit(org.lwjgl.opengl.Display::update);
        q.swapFramesAndSync();
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
    }

    /**
     * 阻塞通道：全屏切换是窗口级变更，语义同 {@link #setDisplayMode(DisplayMode)}。
     *
     * @throws LWJGLException 渲染线程上的真实调用失败
     */
    public static void setFullscreen(boolean fullscreen) throws LWJGLException {
        BridgeSupport.blockingWaitLwjgl(() -> org.lwjgl.opengl.Display.setFullscreen(fullscreen));
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
        return BridgeSupport.blockingGet(() -> org.lwjgl.opengl.Display.setIcon(icons));
    }
}
