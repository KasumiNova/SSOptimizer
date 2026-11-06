package github.kasuminova.ssoptimizer.common.loading;

import java.util.Objects;

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