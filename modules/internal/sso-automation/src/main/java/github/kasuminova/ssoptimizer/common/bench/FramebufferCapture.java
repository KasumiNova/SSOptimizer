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

public final class FramebufferCapture {
    private FramebufferCapture() {
    }

    public static Path captureToPng(final Path outputPath) throws IOException {
        final BufferedImage image = capture();
        Files.createDirectories(outputPath.getParent());
        if (!ImageIO.write(image, "png", outputPath.toFile())) {
            throw new IOException("No PNG writer available");
        }
        return outputPath;
    }

    public static BufferedImage capture() throws IOException {
        return capture(GL11.GL_BACK);
    }

    public static BufferedImage capture(final int readBuffer) throws IOException {
        final int width = Display.getWidth();
        final int height = Display.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IOException("Display size is invalid: " + width + "x" + height);
        }

        final ByteBuffer pixels = BufferUtils.createByteBuffer(width * height * 4);
        GL11.glReadBuffer(readBuffer);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);

        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int srcRow = y * width;
            final int dstRow = (height - y - 1) * width;
            for (int x = 0; x < width; x++) {
                final int index = (srcRow + x) * 4;
                // 无 alpha 平面的 GLX 视觉会将 alpha 读为 0，因此截图像素固定为不透明。
                argb[dstRow + x] = 0xFF000000
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
