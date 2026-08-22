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
 * 探测失败按「全不支持」处理：整特性降级未压缩路径并记 WARN，不抛给调用方。
 * <p>
 * 配置面：{@code ssoptimizer.texcompress.enable=false} 时整特性视为不可用
 * （preferredFormat 恒 NONE）；{@code ssoptimizer.texcompress.format=auto|bc7|bc3}
 * 可收窄首选格式（bc3 强制时即便 bc7 可用也返回 BC3）。
 */
public final class TextureCompressionSupport {
    static final String ENABLE_PROPERTY = "ssoptimizer.texcompress.enable";
    static final String FORMAT_PROPERTY = "ssoptimizer.texcompress.format";

    private static final Logger LOGGER = Logger.getLogger(TextureCompressionSupport.class);

    private static final String EXT_BPTC = "GL_ARB_texture_compression_bptc";
    private static final String EXT_S3TC = "GL_EXT_texture_compression_s3tc";

    /** 探测结果缓存（null = 未探测；探测失败也缓存为全不支持，避免重复 WARN）。 */
    private static volatile ProbeResult cachedProbe;

    /**
     * 压缩纹理形态。本期（T1）仅有探测/诊断用途，上传路径仍全部 NONE；
     * T2 起由压缩管线写入真值。
     */
    public enum Format {
        /** 未压缩 RGBA8（现状路径）。 */
        NONE(32),
        /** BC1/DXT1（4bpp，仅大尺寸无 alpha 纹理）。 */
        BC1(4),
        /** BC3/DXT5（8bpp，插值 alpha，BC7 不可用时的回退档）。 */
        BC3(8),
        /** BC7/BPTC_UNORM（8bpp，首选）。 */
        BC7(8);

        private final int bitsPerPixel;

        Format(final int bitsPerPixel) {
            this.bitsPerPixel = bitsPerPixel;
        }

        /** 每像素位数（显存估算用：none=4B/px、bc1=0.5B/px、bc3/bc7=1B/px）。 */
        int bitsPerPixel() {
            return bitsPerPixel;
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
            cachedProbe = result;
            LOGGER.info("[SSOptimizer] 纹理压缩 GL 能力探测：bc7(bptc)=" + result.bptc()
                    + " bc3/bc1(s3tc)=" + result.s3tc()
                    + " enabled=" + isEnabled()
                    + " preferred=" + preferredFormat());
            return result;
        }
    }

    private static ProbeResult doProbe() {
        if (RenderThreadMode.isEnabled()) {
            // RT：glGetString 调用点经 RenderThreadRedirector 改写到 bridge GL11
            // （扩展串五槽缓存 + 阻塞回读），此处写法与非 RT 一致
            try {
                return parseExtensionSupport(GL11.glGetString(GL11.GL_EXTENSIONS));
            } catch (Throwable t) {
                LOGGER.warn("[SSOptimizer] 纹理压缩能力探测失败（RT 扩展串回读），整特性降级未压缩路径", t);
                return new ProbeResult(false, false);
            }
        }
        try {
            final ContextCapabilities capabilities = GLContext.getCapabilities();
            return new ProbeResult(capabilities.GL_ARB_texture_compression_bptc,
                    capabilities.GL_EXT_texture_compression_s3tc);
        } catch (Throwable t) {
            LOGGER.warn("[SSOptimizer] 纹理压缩能力探测失败（capabilities 字段查询），整特性降级未压缩路径", t);
            return new ProbeResult(false, false);
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
