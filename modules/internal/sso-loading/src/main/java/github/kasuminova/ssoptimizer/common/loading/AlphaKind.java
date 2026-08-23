package github.kasuminova.ssoptimizer.common.loading;

/**
 * 纹理实际像素 alpha 内容分类（{@link TexturePixelConverter} 在既有像素遍历上捎带统计）。
 * <p>
 * 与 {@code ColorModel.hasAlpha()} 的区别：后者只说明图片「声明了 alpha 通道」，
 * 大量贴图声明 RGBA 但 alpha 实际全 255——压缩格式选择必须以实际像素内容为准，
 * 否则全不透明贴图会落到 8bpp 格式浪费一半显存。
 */
public enum AlphaKind {
    /** 全不透明：所有像素 alpha = 255（含无 alpha 通道的图片）。 */
    OPAQUE,
    /** 二值 alpha：只出现 0 和 255（硬边镂空贴图，可用 BC1 1-bit punch-through）。 */
    BINARY,
    /** 完整 alpha：存在 0/255 以外的值（半透明渐变，需要 BC7/BC3 的插值 alpha）。 */
    FULL;

    /**
     * 按序数反解析（缓存落盘用）。
     *
     * @throws IllegalArgumentException 序数越界（缓存数据损坏）
     */
    public static AlphaKind fromOrdinal(final int ordinal) {
        final AlphaKind[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("Unknown AlphaKind ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
