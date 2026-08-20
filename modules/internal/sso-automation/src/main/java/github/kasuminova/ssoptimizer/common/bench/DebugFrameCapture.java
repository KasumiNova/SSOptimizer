package github.kasuminova.ssoptimizer.common.bench;

import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * 调试帧抓取：按帧号触发一次 back buffer 截图写 PNG。
 * <p>
 * 动机：主菜单/战役等非战斗场景的渲染取证（如字体乱码 A/B 对照）——bench 的截图机制
 * 只覆盖任务内（{@code CombatState} 帧尾），主菜单走 {@code TitleScreenState}，加载期
 * 走其他状态。统一挂在 {@code org.lwjgl.opengl.Display#update(boolean)} 上即可覆盖
 * 全部渲染路径（LWJGL2 的 {@code update()} 内部委托 {@code update(true)}）。
 * <p>
 * 触发方式：{@code -Dssoptimizer.debug.framecapture.dir=<目录>} 开启，
 * {@code -Dssoptimizer.debug.framecapture.frame=<帧号>}（默认 600）指定单帧抓取，
 * 或 {@code -Dssoptimizer.debug.framecapture.frames=<帧号,帧号,...>} 多帧采样。
 * 每个帧号只抓一帧；必须在持有 GL 上下文的线程调用（即 Display.update 的调用线程）。
 * <p>
 * rt 黑屏取证增强：每帧同时抓 FRONT（当前显示帧）与 BACK（swap 前本帧绘制结果），
 * 并输出 viewport / scissor / 投影矩阵采样 / 窗口尺寸 / 清空后排队的全部错误码，
 * 用于区分「绘制没执行」「swap 丢失」「默认帧缓冲异常」三类根因。
 */
public final class DebugFrameCapture {
    private static final Logger LOGGER = Logger.getLogger(DebugFrameCapture.class);

    /** 调试帧抓取输出目录（{@code -Dssoptimizer.debug.framecapture.dir}），null 关闭。 */
    private static final String CAPTURE_DIR = System.getProperty("ssoptimizer.debug.framecapture.dir");
    /** 调试帧抓取触发帧号（{@code -Dssoptimizer.debug.framecapture.frame}，默认 600）。 */
    private static final int CAPTURE_FRAME = Integer.getInteger("ssoptimizer.debug.framecapture.frame", 600);
    /**
     * 调试帧抓取触发帧号集合（{@code -Dssoptimizer.debug.framecapture.frames}，
     * 逗号分隔，用于偶发渲染问题的多帧采样；未配置时退化为 {@link #CAPTURE_FRAME} 单帧）。
     */
    private static final java.util.Set<Integer> CAPTURE_FRAMES = parseCaptureFrames();

    /** 已渲染帧计数（仅在开启时递增）。 */
    private static int frameCounter;

    private DebugFrameCapture() {
    }

    private static java.util.Set<Integer> parseCaptureFrames() {
        String list = System.getProperty("ssoptimizer.debug.framecapture.frames");
        if (list == null || list.isBlank()) {
            return java.util.Collections.singleton(CAPTURE_FRAME);
        }
        java.util.Set<Integer> frames = new java.util.TreeSet<>();
        for (String token : list.split(",")) {
            frames.add(Integer.parseInt(token.trim()));
        }
        return frames;
    }

    /** 帧尾回调：到达目标帧号时抓取 front/back buffer 写 PNG 并输出 GL 状态，每帧号只抓一次。 */
    public static void onDisplayUpdate() {
        if (CAPTURE_DIR == null) {
            return;
        }
        frameCounter++;
        if (!CAPTURE_FRAMES.contains(frameCounter)) {
            return;
        }
        try {
            // 先清空排队错误，避免历史残留干扰本次读取的判别
            java.util.Map<Integer, Integer> pendingErrors = drainGlErrors();

            IntBuffer ints = BufferUtils.createIntBuffer(16);
            GL11.glGetInteger(GL11.GL_VIEWPORT, ints);
            String viewport = ints.get(0) + "," + ints.get(1) + " " + ints.get(2) + "x" + ints.get(3);
            ints.clear();
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, ints);
            String scissor = GL11.glGetBoolean(GL11.GL_SCISSOR_TEST)
                    ? ints.get(0) + "," + ints.get(1) + " " + ints.get(2) + "x" + ints.get(3) : "off";
            int fboBinding = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            FloatBuffer floats = BufferUtils.createFloatBuffer(16);
            GL11.glGetFloat(GL11.GL_PROJECTION_MATRIX, floats);
            String proj = "p0=" + floats.get(0) + " p5=" + floats.get(5);

            javax.imageio.ImageIO.write(FramebufferCapture.capture(GL11.GL_FRONT), "png",
                    java.nio.file.Paths.get(CAPTURE_DIR, "frame-" + frameCounter + "-front.png").toFile());
            javax.imageio.ImageIO.write(FramebufferCapture.capture(GL11.GL_BACK), "png",
                    java.nio.file.Paths.get(CAPTURE_DIR, "frame-" + frameCounter + "-back.png").toFile());

            java.util.Map<Integer, Integer> errors = drainGlErrors();
            LOGGER.info("[SSOptimizer] debug frame captured at frame " + frameCounter
                    + " (fboBinding=" + fboBinding
                    + " viewport=" + viewport + " scissor=" + scissor + " " + proj
                    + " display=" + org.lwjgl.opengl.Display.getWidth() + "x" + org.lwjgl.opengl.Display.getHeight()
                    + " active=" + org.lwjgl.opengl.Display.isActive()
                    + " pendingErr=" + formatErrors(pendingErrors)
                    + " captureErr=" + formatErrors(errors) + ")");
        } catch (Exception e) {
            LOGGER.warn("[SSOptimizer] debug frame capture failed at frame " + frameCounter, e);
        }
    }

    /** 循环排空 glGetError 并按错误码聚合计数。 */
    private static java.util.Map<Integer, Integer> drainGlErrors() {
        java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        int err;
        int guard = 0;
        while ((err = GL11.glGetError()) != GL11.GL_NO_ERROR && guard++ < 64) {
            counts.merge(err, 1, Integer::sum);
        }
        return counts;
    }

    private static String formatErrors(final java.util.Map<Integer, Integer> errors) {
        if (errors.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        errors.forEach((code, count) ->
                sb.append("0x").append(Integer.toHexString(code)).append('x').append(count).append(' '));
        return sb.toString().trim();
    }
}
