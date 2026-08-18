package github.kasuminova.ssoptimizer.bridge.opengl;

import github.kasuminova.ssoptimizer.common.bench.FramebufferCapture;
import github.kasuminova.ssoptimizer.common.render.queue.RenderQueue;
import org.lwjgl.LWJGLException;

/**
 * org.lwjgl.opengl.Display 的 bridge 镜像（垂直切片子集）。
 * <p>
 * 动机同 {@link GL11}：ASM 重定向把游戏/模组字节码中
 * {@code org/lwjgl/opengl/Display} 的静态调用改写到本类。其中：
 * <ul>
 *   <li>{@link #update()} 是唯一携带 GL 语义的帧尾调用：入队一条真实
 *       {@code Display.update()}（交换缓冲，必须在持有上下文的渲染线程执行），
 *       随后 {@code swapFramesAndSync()} 完成帧提交与一帧重叠同步；</li>
 *   <li>{@link #create()} 延迟到渲染线程执行（FR 同款设计：主线程从头到尾
 *       没有 GL context），调用方阻塞到创建完成；</li>
 *   <li>{@link #processMessages()}/{@link #isCloseRequested()}/{@link #isActive()}/
 *       {@link #getWidth()}/{@link #getHeight()} 无 GL 上下文依赖，本阶段直通
 *       真实 Display（注释保留：若后续把事件泵也迁到渲染线程，这里改为状态仿真
 *       或帧同步点读取）。</li>
 * </ul>
 */
public final class Display {
    private static volatile RenderQueue queue;

    private Display() {
    }

    /**
     * 安装命令消费者，语义同 {@link GL11#install(RenderQueue)}。
     *
     * @param renderQueue 渲染队列实例
     */
    public static void install(RenderQueue renderQueue) {
        queue = renderQueue;
    }

    /** 测试用：卸载已安装的队列，避免用例间静态状态串扰。 */
    static void uninstall() {
        queue = null;
    }

    private static RenderQueue queue() {
        RenderQueue q = queue;
        if (q == null) {
            throw new IllegalStateException("[SSOptimizer] bridge Display 的 RenderQueue 未安装（Display.install 未被调用）");
        }
        return q;
    }

    /**
     * 在渲染线程创建 Display（含 GL 上下文），调用方阻塞至创建完成。
     * 结构对齐 FR：主线程全程无 context，创建语义经队列的阻塞通道落实。
     *
     * @throws LWJGLException 渲染线程上的真实 {@code Display.create()} 失败
     */
    public static void create() throws LWJGLException {
        RenderQueue q = queue();
        LWJGLException[] failure = new LWJGLException[1];
        q.wait(() -> {
            try {
                org.lwjgl.opengl.Display.create();
            } catch (LWJGLException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** 调试帧抓取输出目录（{@code -Dssoptimizer.debug.framecapture.dir}），null 关闭。 */
    private static final String DEBUG_CAPTURE_DIR = System.getProperty("ssoptimizer.debug.framecapture.dir");
    /** 调试帧抓取触发帧号（{@code -Dssoptimizer.debug.framecapture.frame}，默认 600）。 */
    private static final int    DEBUG_CAPTURE_FRAME = Integer.getInteger("ssoptimizer.debug.framecapture.frame", 600);
    /** 渲染线程帧计数（仅调试抓取使用）。 */
    private static int debugFrameCounter;

    /**
     * 调试帧抓取：渲染线程在 swap 前抓取 back buffer 写 PNG（主菜单/非战斗场景的
     * 渲染取证手段，bench 的截图机制只覆盖任务内）。每进程只抓一帧。
     */
    private static void debugCaptureFrame() {
        if (DEBUG_CAPTURE_DIR == null) {
            return;
        }
        debugFrameCounter++;
        if (debugFrameCounter != DEBUG_CAPTURE_FRAME) {
            return;
        }
        try {
            FramebufferCapture.captureToPng(
                    java.nio.file.Paths.get(DEBUG_CAPTURE_DIR, "frame-" + debugFrameCounter + ".png"));
        } catch (Exception e) {
            org.apache.log4j.Logger.getLogger(Display.class).warn(
                    "[SSOptimizer] debug frame capture failed at frame " + debugFrameCounter, e);
        }
    }

    /**
     * 帧尾：入队真实 {@code Display.update()}（渲染线程交换缓冲），
     * 然后提交当前帧并只等待上一帧完成（一帧流水线重叠）。
     */
    public static void update() {
        RenderQueue q = queue();
        q.submit(() -> {
            debugCaptureFrame();
            org.lwjgl.opengl.Display.update();
        });
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

    /** 直通：窗口尺寸读的是 LWJGL 缓存字段，无 GL 依赖。 */
    public static int getWidth() {
        return org.lwjgl.opengl.Display.getWidth();
    }

    /** 直通：同 {@link #getWidth()}。 */
    public static int getHeight() {
        return org.lwjgl.opengl.Display.getHeight();
    }
}
