package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.util.Locale;

/**
 * GL 能力查询失败时不缓存结果，后续在持有 GL 上下文的线程上重试。
 */
public final class TextureCompressionSupport {
    static final String ENABLE_PROPERTY = "ssoptimizer.texcompress.enable";
    static final String FORMAT_PROPERTY = "ssoptimizer.texcompress.format";
    static final String BC1_FOR_OPAQUE_PROPERTY = "ssoptimizer.texcompress.bc1ForOpaque";
    static final String MODE_PROPERTY   = "ssoptimizer.texcompress.mode";

    private static final Logger LOGGER = Logger.getLogger(TextureCompressionSupport.class);

    private static final String EXT_BPTC = "GL_ARB_texture_compression_bptc";
    private static final String EXT_S3TC = "GL_EXT_texture_compression_s3tc";

    private static volatile ProbeResult cachedProbe;
    private static final java.util.concurrent.atomic.AtomicBoolean PROBE_FAILURE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public enum Format {
        NONE(32, "none", 6408, 0),
        BC1(4, "bc1", 0x83F1, NativeTextureCompressor.FORMAT_BC1),
        BC3(8, "bc3", 0x83F3, NativeTextureCompressor.FORMAT_BC3),
        BC7(8, "bc7", 0x8E8C, NativeTextureCompressor.FORMAT_BC7);

        private final int    bitsPerPixel;
        private final String tag;
        private final int    glInternalFormat;
        private final int    nativeId;

        Format(final int bitsPerPixel,
               final String tag,
               final int glInternalFormat,
               final int nativeId) {
            this.bitsPerPixel = bitsPerPixel;
            this.tag = tag;
            this.glInternalFormat = glInternalFormat;
            this.nativeId = nativeId;
        }

        int bitsPerPixel() {
            return bitsPerPixel;
        }

        String tag() {
            return tag;
        }

        int glInternalFormat() {
            return glInternalFormat;
        }

        int nativeId() {
            return nativeId;
        }

        static Format fromNativeId(final int nativeId) {
            for (final Format format : values()) {
                if (format.nativeId == nativeId && format != NONE) {
                    return format;
                }
            }
            return null;
        }
    }

    private TextureCompressionSupport() {
    }

    public static boolean isBc7Available() {
        return isEnabled() && probeOnce().bptc();
    }

    public static boolean isS3tcAvailable() {
        return isEnabled() && probeOnce().s3tc();
    }

    public static Format preferredFormat() {
        final ProbeResult probe = probeOnce();
        return decideFormat(isEnabled(), System.getProperty(FORMAT_PROPERTY), probe.bptc(), probe.s3tc());
    }

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    static boolean isBc1ForOpaqueEnabled() {
        return Boolean.parseBoolean(System.getProperty(BC1_FOR_OPAQUE_PROPERTY, "false"));
    }

    static boolean isEagerMode() {
        return parseMode(System.getProperty(MODE_PROPERTY));
    }

    static boolean parseMode(final String modeProperty) {
        return modeProperty != null && "eager".equals(modeProperty.trim().toLowerCase(Locale.ROOT));
    }

    private static ProbeResult probeOnce() {
        final ProbeResult cached = cachedProbe;
        if (cached != null) {
            return cached;
        }
        synchronized (TextureCompressionSupport.class) {
            if (cachedProbe != null) {
                return cachedProbe;
            }
            final ProbeResult result = doProbe();
            if (result == null) {
                if (PROBE_FAILURE_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warn("[SSOptimizer] 纹理压缩能力探测失败（当前线程无 GL 上下文），"
                            + "本次按全不支持处理，将在持上下文线程上重试");
                }
                return new ProbeResult(false, false);
            }
            cachedProbe = result;
            LOGGER.info("[SSOptimizer] 纹理压缩 GL 能力探测：bc7(bptc)=" + result.bptc()
                    + " bc3/bc1(s3tc)=" + result.s3tc()
                    + " enabled=" + isEnabled()
                    + " preferred=" + preferredFormat());
            final String vram = VramProbe.queryStatus();
            if (vram != null) {
                LOGGER.info("[SSOptimizer] VRAM 基线: " + vram);
            }
            return result;
        }
    }

    private static ProbeResult doProbe() {
        if (RenderThreadMode.isEnabled()) {
            // 渲染分离模式下该调用由 RenderThreadRedirector 重定向到 bridge。
            try {
                final String extensions = GL11.glGetString(GL11.GL_EXTENSIONS);
                if (extensions == null || extensions.isBlank()) {
                    return null;
                }
                return parseExtensionSupport(extensions);
            } catch (Throwable t) {
                return null;
            }
        }
        try {
            final ContextCapabilities capabilities = GLContext.getCapabilities();
            return new ProbeResult(capabilities.GL_ARB_texture_compression_bptc,
                    capabilities.GL_EXT_texture_compression_s3tc);
        } catch (Throwable t) {
            return null;
        }
    }

    static ProbeResult parseExtensionSupport(final String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return new ProbeResult(false, false);
        }
        final String padded = ' ' + extensions + ' ';
        return new ProbeResult(
                padded.contains(' ' + EXT_BPTC + ' '),
                padded.contains(' ' + EXT_S3TC + ' '));
    }

    static Format decideFormat(final boolean enabled,
                               final String formatProperty,
                               final boolean bptcSupported,
                               final boolean s3tcSupported) {
        if (!enabled) {
            return Format.NONE;
        }
        final String mode = formatProperty == null
                ? "auto"
                : formatProperty.trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case "bc7" -> bptcSupported ? Format.BC7 : Format.NONE;
            case "bc3" -> s3tcSupported ? Format.BC3 : Format.NONE;
            default -> bptcSupported ? Format.BC7 : (s3tcSupported ? Format.BC3 : Format.NONE);
        };
    }

    record ProbeResult(boolean bptc, boolean s3tc) {
    }
}
