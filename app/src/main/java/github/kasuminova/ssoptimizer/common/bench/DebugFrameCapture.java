package github.kasuminova.ssoptimizer.common.bench;

import org.apache.log4j.Logger;

/**
 * 调试帧抓取：按帧号触发一次 back buffer 截图写 PNG。
 * <p>
 * 动机：主菜单/战役等非战斗场景的渲染取证（如字体乱码 A/B 对照）——bench 的截图机制
 * 只覆盖任务内（{@code CombatState} 帧尾），主菜单走 {@code TitleScreenState}，加载期
 * 走其他状态。统一挂在 {@code org.lwjgl.opengl.Display#update(boolean)} 上即可覆盖
 * 全部渲染路径（LWJGL2 的 {@code update()} 内部委托 {@code update(true)}）。
 * <p>
 * 触发方式：{@code -Dssoptimizer.debug.framecapture.dir=<目录>} 开启，
 * {@code -Dssoptimizer.debug.framecapture.frame=<帧号>}（默认 600）指定抓取帧。
 * 每进程只抓一帧；必须在持有 GL 上下文的线程调用（即 Display.update 的调用线程）。
 */
public final class DebugFrameCapture {
    private static final Logger LOGGER = Logger.getLogger(DebugFrameCapture.class);

    /** 调试帧抓取输出目录（{@code -Dssoptimizer.debug.framecapture.dir}），null 关闭。 */
    private static final String CAPTURE_DIR = System.getProperty("ssoptimizer.debug.framecapture.dir");
    /** 调试帧抓取触发帧号（{@code -Dssoptimizer.debug.framecapture.frame}，默认 600）。 */
    private static final int CAPTURE_FRAME = Integer.getInteger("ssoptimizer.debug.framecapture.frame", 600);

    /** 已渲染帧计数（仅在开启时递增）。 */
    private static int frameCounter;

    private DebugFrameCapture() {
    }

    /** 帧尾回调：到达目标帧号时抓取 back buffer 写 PNG，随后不再动作。 */
    public static void onDisplayUpdate() {
        if (CAPTURE_DIR == null) {
            return;
        }
        frameCounter++;
        if (frameCounter != CAPTURE_FRAME) {
            return;
        }
        try {
            FramebufferCapture.captureToPng(
                    java.nio.file.Paths.get(CAPTURE_DIR, "frame-" + frameCounter + ".png"));
            LOGGER.info("[SSOptimizer] debug frame captured at frame " + frameCounter);
        } catch (Exception e) {
            LOGGER.warn("[SSOptimizer] debug frame capture failed at frame " + frameCounter, e);
        }
    }
}
