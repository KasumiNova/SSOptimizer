package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;

import java.awt.image.*;
import java.io.IOException;

/**
 * 原生 PNG 解码器。
 * <p>
 * 通过 JNI 调用 C++ 实现的 PNG 解码，可并行解码多张图片，显著提升资源加载速度。
 * 通过 {@code -Dssoptimizer.disable.nativepngdecoder} 可禁用。
 */
public final class NativePngDecoder {
    private static final String DISABLE_PROPERTY = "ssoptimizer.disable.nativepngdecoder";
    private static final int[]  ARGB_MASKS       = {
            0x00FF0000,
            0x0000FF00,
            0x000000FF,
            0xFF000000
    };

    private static volatile Boolean nativeBackendSupported;

    private NativePngDecoder() {
    }

    public static BufferedImage decode(final byte[] imageBytes) throws IOException {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("native PNG decoder unavailable");
        }

        NativeDecodedImage decoded = decodeRaw(imageBytes);
        if (decoded == null) {
            throw new IOException("native PNG decoder returned null");
        }
        return toBufferedImage(decoded);
    }

    static NativeDecodedImage decodeRaw(final byte[] imageBytes) throws IOException {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("native PNG decoder unavailable");
        }
        return decodePng0(imageBytes);
    }

    static NativeDecodedImage benchmarkBridge(final byte[] imageBytes,
                                              final int width,
                                              final int height) {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("native PNG decoder unavailable");
        }
        return benchmarkBridge0(imageBytes, width, height);
    }

    public static boolean isAvailable() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        if (!NativeRuntime.isLoaded()) {
            return false;
        }

        Boolean cached = nativeBackendSupported;
        if (cached != null) {
            return cached;
        }

        synchronized (NativePngDecoder.class) {
            if (nativeBackendSupported != null) {
                return nativeBackendSupported;
            }

            boolean supported = nativeIsSupported();
            nativeBackendSupported = supported;
            return supported;
        }
    }

    private static BufferedImage toBufferedImage(final NativeDecodedImage decoded) {
        DataBufferInt dataBuffer = new DataBufferInt(decoded.argbPixels(), decoded.argbPixels().length);
        WritableRaster raster = Raster.createPackedRaster(
                dataBuffer,
                decoded.width(),
                decoded.height(),
                decoded.width(),
                ARGB_MASKS,
                null
        );
        return new BufferedImage(ColorModel.getRGBdefault(), raster, false, null);
    }

    private static native boolean nativeIsSupported();

    private static native NativeDecodedImage decodePng0(byte[] imageBytes) throws IOException;

    private static native NativeDecodedImage benchmarkBridge0(byte[] imageBytes, int width, int height);
}