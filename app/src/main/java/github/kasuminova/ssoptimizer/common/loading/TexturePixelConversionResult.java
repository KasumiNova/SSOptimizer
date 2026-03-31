package github.kasuminova.ssoptimizer.common.loading;

import java.awt.*;
import java.nio.ByteBuffer;

public record TexturePixelConversionResult(ByteBuffer buffer,
                                           int textureWidth,
                                           int textureHeight,
                                           Color averageColor,
                                           Color upperHalfColor,
                                           Color lowerHalfColor) {
}