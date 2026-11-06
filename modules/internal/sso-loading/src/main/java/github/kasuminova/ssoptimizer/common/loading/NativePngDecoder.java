package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

import java.awt.image.*;
import java.io.IOException;

public final class NativePngDecoder {
    private static final Logger LOGGER = Logger.getLogger(NativePngDecoder.class);

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
        try {
            return decodePng0(imageBytes);
        } catch (UnsatisfiedLinkError e) {
            throw unavailable("decode", e);
        }
    }

    static NativeDecodedImage benchmarkBridge(final byte[] imageBytes,
                                              final int width,
                                              final int height) {
        if (!isAvailable()) {
            throw new UnsupportedOperationException("native PNG decoder unavailable");
        }
        try {
            return benchmarkBridge0(imageBytes, width, height);
        } catch (UnsatisfiedLinkError e) {
            throw unavailable("benchmark bridge", e);
        }
    }

    public static boolean isAvailable() {
        if (Boolean.getBoolean(DISABLE_PROPERTY)) {
            return false;
        }
        if (!NativeRuntime.loadModule("loading")) {
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

            try {
                boolean supported = nativeIsSupported();
                nativeBackendSupported = supported;
                return supported;
            } catch (UnsatisfiedLinkError e) {
                markUnavailable("support probe", e);
                return false;
            }
        }
    }

    private static UnsupportedOperationException unavailable(final String operation,
                                                              final UnsatisfiedLinkError error) {
        markUnavailable(operation, error);
        return new UnsupportedOperationException("native PNG decoder unavailable", error);
    }

    private static void markUnavailable(final String operation, final UnsatisfiedLinkError error) {
        nativeBackendSupported = false;
        LOGGER.error("[SSOptimizer] Native PNG decoder " + operation + " link failed; decoder disabled.", error);
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