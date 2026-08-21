package github.kasuminova.ssoptimizer.common.font.atlas;

/**
 * 字形栅格化回调：{@link DynamicGlyphAtlas} 未命中时同步调用，向调用方
 * （{@code TtfGlyphProvider}）屏蔽 FreeType/JNI 细节——图集只消费位图，
 * 不认识字体后端；测试注入内存假实现即可覆盖分配/淘汰逻辑。
 * <p>
 * 实现约定：返回白底 alpha（argbPixels 的 alpha 通道为覆盖率）的位图；
 * 图集只取 alpha 通道写入 GL_ALPHA8 页。
 */
@FunctionalInterface
public interface GlyphRasterizer {

    /**
     * 栅格化单个字形。
     *
     * @param codePoint     字符码点（调用方已完成 victor 小写→大写等语义转换）
     * @param baseline      基线（face 像素坐标系，fnt base × bucketScale）
     * @param strokeWidthPx 描边宽度（设备像素，0 = 纯填充）
     * @return 栅格化位图；失败返回 {@code null}（图集按缺失处理，调用方回退 '?'）
     */
    github.kasuminova.ssoptimizer.common.font.NativeGlyphBitmap rasterize(
            int codePoint, int baseline, float strokeWidthPx);
}
