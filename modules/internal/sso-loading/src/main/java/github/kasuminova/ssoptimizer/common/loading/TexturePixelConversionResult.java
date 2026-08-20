package github.kasuminova.ssoptimizer.common.loading;

import java.awt.*;
import java.nio.ByteBuffer;

/**
 * 贴图像素转换结果。
 * <p>
 * 封装 OpenGL 贴图上传所需的像素缓冲区、尺寸和均色信息。
 */
public record TexturePixelConversionResult(ByteBuffer buffer,
                                           int textureWidth,
                                           int textureHeight,
                                           Color averageColor,
                                           Color upperHalfColor,
                                           Color lowerHalfColor) {
}