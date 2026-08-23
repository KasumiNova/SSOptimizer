package github.kasuminova.ssoptimizer.common.loading;

import github.kasuminova.ssoptimizer.common.render.runtime.NativeRuntime;
import org.apache.log4j.Logger;

import java.nio.ByteBuffer;

/**
 * 原生 BC 纹理压缩器（bc7enc + rgbcx，libssoptimizer_texcompress）。
 * <p>
 * 输入为即将上传 GL 的 RGBA8 像素缓冲（与未压缩上传同一份数据、同一行序约定，
 * 因此压缩结果直接 glCompressedTexImage2D 上传即保持朝向一致）；
 * mip 链由 native 侧逐级下采样生成（压缩纹理不可依赖 GL_GENERATE_MIPMAP）。
 * <p>
 * 输出为 SSOBC 容器字节（未做 zstd，二压由 CompressedTextureCache 落盘层负责；
 * 多字节字段一律小端）：
 * <pre>
 * 头（16B）：magic "SSOB"(4) | version u8=1 | format u8 | mipCount u16 | width u32 | height u32
 * 级别表（每级 16B）：w u32 | h u32 | dataLen u32 | reserved u32
 * 随后按级别顺序紧密拼接各级压缩块数据。
 * </pre>
 */
public final class NativeTextureCompressor {
    /** BC1（S3TC DXT1，4bpp，仅大尺寸无 alpha 纹理）。 */
    public static final int FORMAT_BC1 = 1;
    /** BC3（S3TC DXT5，8bpp，BC7 不可用时的回退档）。 */
    public static final int FORMAT_BC3 = 3;
    /** BC7（BPTC_UNORM，8bpp，首选）。 */
    public static final int FORMAT_BC7 = 7;

    /** 质量档：快速。 */
    public static final int QUALITY_FAST   = 0;
    /** 质量档：普通（默认，bc7enc normal / rgbcx level 12）。 */
    public static final int QUALITY_NORMAL = 1;
    /** 质量档：高。 */
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

    /**
     * 压缩 RGBA8 像素为 BC 块（含 mip 链）。
     *
     * @param format     {@link #FORMAT_BC1}/{@link #FORMAT_BC3}/{@link #FORMAT_BC7}
     * @param rgbaPixels direct ByteBuffer，紧密 RGBA8，长度为 width*height*4
     * @param mipLevels  mip 层数（1 = 仅 base 级）；超过尺寸允许层数时 native 侧收敛
     * @param quality    {@link #QUALITY_FAST}/{@link #QUALITY_NORMAL}/{@link #QUALITY_HIGH}
     * @param useAlpha   仅 BC1 有效：true 时含透明像素的块按 1-bit punch-through alpha
     *                   编码（3-color 模式，selector 3 = 透明），且 mip 下采样的 alpha
     *                   通道按二值多数决保持 0/255；BC3/BC7 忽略此参数
     * @return SSOBC 容器字节；失败返回 null（本方法记 warn，调用方按「压缩不可用」降级）
     */
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
