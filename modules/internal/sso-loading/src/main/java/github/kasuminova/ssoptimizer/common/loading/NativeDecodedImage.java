package github.kasuminova.ssoptimizer.common.loading;

import java.util.Objects;

/**
 * 原生 PNG 解码结果。
 * <p>
 * 存储 C++ 解码器返回的 ARGB 像素数据和图像尺寸。
 */
public record NativeDecodedImage(int width, int height, int[] argbPixels) {
    public NativeDecodedImage {
        if (width <= 0) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0) {
            throw new IllegalArgumentException("height must be positive");
        }

        Objects.requireNonNull(argbPixels, "argbPixels");
        int expectedLength = Math.multiplyExact(width, height);
        if (argbPixels.length != expectedLength) {
            throw new IllegalArgumentException(
                    "pixel count mismatch: expected=" + expectedLength + ", actual=" + argbPixels.length
            );
        }
    }
}