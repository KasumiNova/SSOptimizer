package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.RenderThreadMode;
import org.apache.log4j.Logger;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import java.util.Locale;

/**
 * GPU 纹理压缩（BC 族）的 GL 能力探测与格式决策（设计：docs/design/gpu-texture-compression.md §4.5）。
 * <p>
 * 探测 {@code GL_ARB_texture_compression_bptc}（BC7 可用）与
 * {@code GL_EXT_texture_compression_s3tc}（BC3/BC1 可用），结果静态缓存
 * （模式同 {@link TextureDimensionSupport}）。
 * <p>
 * 渲染线程分离（RT）模式下扩展字符串查询经 bridge 回读：本类中的
 * {@code GL11.glGetString} 调用点会被 RenderThreadRedirector 改写 owner 到
 * bridge GL11，由其实现录制侧缓存 + 阻塞回读（一次性罕见操作，可接受）；
 * 非 RT 模式直接读 {@link GLContext#getCapabilities()} 的能力字段。
 * 探测失败（当前线程无 GL 上下文等）按「全不支持」处理、不抛给调用方，但失败结果
     * 不缓存——T2 起 worker 线程也会触发判定（ssotex 抑制），缓存失败会把整会话毒化为
     * 不可用；失败 WARN 只记一次，后续调用在持上下文线程上重试直至成功缓存。
 * <p>
 * 配置面：{@code ssoptimizer.texcompress.enable=false} 时整特性视为不可用
 * （preferredFormat 恒 NONE）；{@code ssoptimizer.texcompress.format=auto|bc7|bc3}
 * 可收窄首选格式（bc3 强制时即便 bc7 可用也返回 BC3）。
 */
public final class TextureCompressionSupport {
    static final String ENABLE_PROPERTY = "ssoptimizer.texcompress.enable";
    static final String FORMAT_PROPERTY = "ssoptimizer.texcompress.format";
    /**
     * BC1 显存优先开关（默认 false）：bptc 可用时，实际像素全不透明的大图
     * （max(w,h) ≥ 256）也用 BC1（4bpp，显存再减半），代价是平滑渐变可能出现
     * 4bpp 块效应色带；false 时 bptc 可用即一律 BC7（质量优先）。
     */
    static final String BC1_FOR_OPAQUE_PROPERTY = "ssoptimizer.texcompress.bc1ForOpaque";
    /**
     * 压缩时机模式：{@code background}（默认，后台线程压缩，首轮未压缩上传）/
     * {@code eager}（加载时同步压缩，首轮即压缩形态上传——首轮加载耗时显著增加，
     * 建议搭配 {@code ssoptimizer.texcompress.quality=fast}）。
     */
    static final String MODE_PROPERTY   = "ssoptimizer.texcompress.mode";

    private static final Logger LOGGER = Logger.getLogger(TextureCompressionSupport.class);

    private static final String EXT_BPTC = "GL_ARB_texture_compression_bptc";
    private static final String EXT_S3TC = "GL_EXT_texture_compression_s3tc";

    /** 探测结果缓存（null = 未探测；仅缓存「探测动作成功执行」的结果，见 {@link #probeOnce}）。 */
    private static volatile ProbeResult cachedProbe;
    /** 探测失败 WARN 只记一次（失败不缓存，后续在持 GL 上下文的线程上会重试）。 */
    private static final java.util.concurrent.atomic.AtomicBoolean PROBE_FAILURE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * 压缩纹理形态。T1 仅有探测/诊断用途；T2 起由压缩管线（上传分流/后台压缩）写入真值。
     * <p>
     * 附带三处管线元数据：{@code tag}（缓存目录/键成分）、{@code glInternalFormat}
     * （{@code glCompressedTexImage2D} 的 internalformat）、{@code nativeId}
     * （{@link NativeTextureCompressor} 的格式常量）。
     */
    public enum Format {
        /** 未压缩 RGBA8（现状路径）。 */
        NONE(32, "none", 6408, 0),
        /** BC1/DXT1（4bpp）：S3TC 回退路径下实际不透明/二值 alpha 的大图（后者带 1-bit
         * punch-through alpha），或 {@code texcompress.bc1ForOpaque=true} 时的不透明大图。 */
        BC1(4, "bc1", 0x83F1, NativeTextureCompressor.FORMAT_BC1),
        /** BC3/DXT5（8bpp，插值 alpha，BC7 不可用时的回退档）。 */
        BC3(8, "bc3", 0x83F3, NativeTextureCompressor.FORMAT_BC3),
        /** BC7/BPTC_UNORM（8bpp，首选）。 */
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

        /** 每像素位数（显存估算用：none=4B/px、bc1=0.5B/px、bc3/bc7=1B/px）。 */
        int bitsPerPixel() {
            return bitsPerPixel;
        }

        /** 缓存目录/键成分用的格式标签（bc1/bc3/bc7）。 */
        String tag() {
            return tag;
        }

        /** GL 压缩纹理 internalformat（BC1=RGBA_DXT1、BC3=RGBA_DXT5、BC7=RGBA_BPTC_UNORM）。 */
        int glInternalFormat() {
            return glInternalFormat;
        }

        /** {@link NativeTextureCompressor} 的格式常量（NONE=0，不参与压缩调用）。 */
        int nativeId() {
            return nativeId;
        }

        /** native 格式常量 → 枚举；未知值返回 null（容器损坏/编码器版本不符）。 */
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

    /**
     * @return BC7（bptc）是否可用；{@code texcompress.enable=false} 时恒 false
     */
    public static boolean isBc7Available() {
        return isEnabled() && probeOnce().bptc();
    }

    /**
     * @return BC3/BC1（s3tc）是否可用；{@code texcompress.enable=false} 时恒 false
     */
    public static boolean isS3tcAvailable() {
        return isEnabled() && probeOnce().s3tc();
    }

    /**
     * 首选压缩格式：bc7 优先、bc3 回退、皆不可用或总开关关闭时为 NONE。
     *
     * @return 当前环境下的首选格式
     */
    public static Format preferredFormat() {
        final ProbeResult probe = probeOnce();
        return decideFormat(isEnabled(), System.getProperty(FORMAT_PROPERTY), probe.bptc(), probe.s3tc());
    }

    static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLE_PROPERTY, "true"));
    }

    /** BC1 显存优先开关（默认 false，见 {@link #BC1_FOR_OPAQUE_PROPERTY}）。 */
    static boolean isBc1ForOpaqueEnabled() {
        return Boolean.parseBoolean(System.getProperty(BC1_FOR_OPAQUE_PROPERTY, "false"));
    }

    /** eager 同步压缩模式是否启用（默认 background）。 */
    static boolean isEagerMode() {
        return parseMode(System.getProperty(MODE_PROPERTY));
    }

    /** 模式解析（纯函数，单测直调）：仅 "eager"（忽略大小写/空白）为 true，其余均 background。 */
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
                // 查询本身失败（典型：非 RT 模式下从不持 GL 上下文的 worker 线程首次调用）：
                // 不缓存失败结果，避免「首次探测落在线程外」把整会话毒化为全不支持；
                // 本次按全不支持返回，WARN 只记一次，后续在持上下文线程上重试。
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

    /**
     * 执行一次扩展探测。
     *
     * @return 探测结果（含「查询成功但两扩展均缺失」）；查询动作本身失败返回 null
     */
    private static ProbeResult doProbe() {
        if (RenderThreadMode.isEnabled()) {
            // RT：glGetString 调用点经 RenderThreadRedirector 改写到 bridge GL11
            // （扩展串五槽缓存 + 阻塞回读），此处写法与非 RT 一致
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

    /**
     * 扩展字符串解析（纯函数，单测直调）：空格分隔的扩展名中查找 bptc/s3tc。
     *
     * @param extensions {@code glGetString(GL_EXTENSIONS)} 结果（可为 null）
     * @return 两个扩展的支持情况
     */
    static ProbeResult parseExtensionSupport(final String extensions) {
        if (extensions == null || extensions.isBlank()) {
            return new ProbeResult(false, false);
        }
        // 首尾补空格做整词匹配，避免子串误命中
        final String padded = ' ' + extensions + ' ';
        return new ProbeResult(
                padded.contains(' ' + EXT_BPTC + ' '),
                padded.contains(' ' + EXT_S3TC + ' '));
    }

    /**
     * 格式决策（纯函数，单测直调）：总开关关闭 → NONE；format 属性收窄
     * （bc3 强制时即便 bc7 可用也返回 BC3；强制格式无对应能力 → NONE，
     * 不做跨格式静默降级）；auto/未知值 → bc7 优先、bc3 回退。
     *
     * @param enabled        {@code texcompress.enable} 解析结果
     * @param formatProperty {@code texcompress.format} 原始值（可为 null）
     * @param bptcSupported  bptc 扩展是否可用
     * @param s3tcSupported  s3tc 扩展是否可用
     * @return 首选格式
     */
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

    /** 一次探测的两扩展结果。 */
    record ProbeResult(boolean bptc, boolean s3tc) {
    }
}
