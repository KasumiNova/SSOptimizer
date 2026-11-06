package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;

public final class NativeTextureCompressor {
    public static final int FORMAT_BC1 = 1;
    public static final int FORMAT_BC3 = 3;
    public static final int FORMAT_BC7 = 7;

    public static final int QUALITY_FAST   = 0;
    public static final int QUALITY_NORMAL = 1;
    public static final int QUALITY_HIGH   = 2;

    private static final Logger LOGGER = Logger.getLogger(NativeTextureCompressor.class);

    private static volatile Boolean nativeBackendSupported;

    private NativeTextureCompressor() {
    }

    public static boolean isAvailable() {
        if (!NativeRuntime.loadModule("texcompress")) {
            return false;
        }

        Boolean cached = nativeBackendSupported;
        if (cached != null) {
            return cached;
        }

        synchronized (NativeTextureCompressor.class) {
            if (nativeBackendSupported != null) {
                return nativeBackendSupported;
            }

            boolean supported = nativeIsSupported();
            nativeBackendSupported = supported;
            return supported;
        }
    }

    // rgbaPixels 必须是紧密 RGBA8 的 direct ByteBuffer，native 压缩失败时返回 null。
    public static byte[] compress(final int format,
                                  final ByteBuffer rgbaPixels,
                                  final int width,
                                  final int height,
                                  final int mipLevels,
                                  final int quality,
                                  final boolean useAlpha) {
        if (!isAvailable()) {
            return null;
        }
        if (rgbaPixels == null || !rgbaPixels.isDirect()
                || rgbaPixels.remaining() < width * (long) height * 4) {
            LOGGER.warn("[SSOptimizer] NativeTextureCompressor.compress: 非法像素缓冲 w=" + width + " h=" + height);
            return null;
        }
        try {
            return nativeCompress(format, rgbaPixels, width, height, mipLevels, quality, useAlpha);
        } catch (RuntimeException e) {
            LOGGER.warn("[SSOptimizer] native BC 压缩失败: format=" + format + " " + width + "x" + height, e);
            return null;
        }
    }

    private static native boolean nativeIsSupported();

    private static native byte[] nativeCompress(int format, ByteBuffer rgbaPixels,
                                                int width, int height, int mipLevels, int quality,
                                                boolean useAlpha);
}
