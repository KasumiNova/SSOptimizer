package github.kasuminova.ssoptimizer.common.bench;

import org.apache.log4j.Logger;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** 必须在持有 GL 上下文的 Display.update 调用线程执行。 */
public final class DebugFrameCapture {
    private static final Logger LOGGER = Logger.getLogger(DebugFrameCapture.class);

    private static final String CAPTURE_DIR = System.getProperty("ssoptimizer.debug.framecapture.dir");
    private static final int CAPTURE_FRAME = Integer.getInteger("ssoptimizer.debug.framecapture.frame", 600);
    private static final java.util.Set<Integer> CAPTURE_FRAMES = parseCaptureFrames();

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

    public static void onDisplayUpdate() {
        if (CAPTURE_DIR == null) {
            return;
        }
        frameCounter++;
        if (!CAPTURE_FRAMES.contains(frameCounter)) {
            return;
        }
        try {
            // 先排空历史 GL 错误，避免污染本次抓帧诊断。
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
