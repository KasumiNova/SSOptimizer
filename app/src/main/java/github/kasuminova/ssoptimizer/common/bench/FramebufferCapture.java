package github.kasuminova.ssoptimizer.common.bench;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 跨平台 OpenGL 帧缓冲截图。
 *
 * <p>在帧渲染完成、swap buffers 之前通过 {@code glReadPixels} 读取 back buffer，
 * 纵向翻转后以 PNG 写出。仅依赖 LWJGL + ImageIO，Linux/Windows/macOS 行为一致。</p>
 */
public final class FramebufferCapture {
    private FramebufferCapture() {
    }

    /**
     * 抓取当前 back buffer 并写出 PNG。
     *
     * @param outputPath 输出 PNG 路径（父目录自动创建）
     * @return 实际写出的路径
     * @throws IOException 尺寸非法或写出失败时抛出
     */
    public static Path captureToPng(final Path outputPath) throws IOException {
        final BufferedImage image = capture();
        Files.createDirectories(outputPath.getParent());
        if (!ImageIO.write(image, "png", outputPath.toFile())) {
            throw new IOException("No PNG writer available");
        }
        return outputPath;
    }

    /**
     * 抓取当前 back buffer 为 ARGB 图像。
     *
     * @return 纵向翻转后的帧图像
     * @throws IOException Display 尺寸非法时抛出
     */
    public static BufferedImage capture() throws IOException {
        final int width = Display.getWidth();
        final int height = Display.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("Display size is invalid: " + width + "x" + height);
        }

        final ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadBuffer(GL11.GL_BACK);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int srcRow = y * width;
            final int dstRow = (height - y - 1) * width;
            for (int x = 0; x < width; x++) {
                final int index = (srcRow + x) * 4;
                argb[dstRow + x] = (pixels.get(index + 3) << 24)
                        | ((pixels.get(index) & 0xFF) << 16)
                        | ((pixels.get(index + 1) & 0xFF) << 8)
                        | (pixels.get(index + 2) & 0xFF);
            }
        }

        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, argb, 0, width);
        return image;
    }
}
