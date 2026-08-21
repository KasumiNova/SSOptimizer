package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 字形度量与 kerning 的查询来源。
 * <p>
 * 动机：{@link TextLayoutEngine} 不直接依赖游戏 BitmapFont 类，
 * 经本接口查询字形数据——位图字体（原版 fnt）与 TTF 动态图集
 * 各自提供实现，引擎逻辑与字形来源解耦。
 */
public interface GlyphProvider {

    /**
     * 按 Unicode 码点查字形度量。
     *
     * @param codePoint 字符码点
     * @return 字形度量；缺失返回 {@code null}（调用方按原版语义回退到码点 63）
     */
    GlyphMetrics glyph(int codePoint);

    /**
     * 查询前后字符的 kerning 调整量（像素，未缩放）。
     *
     * @param prevCodePoint 前一字符码点
     * @param codePoint     当前字符码点
     * @return kerning 值；无该字形对返回 {@code null}
     */
    Integer kerning(int prevCodePoint, int codePoint);

    /**
     * 字体名义字号（BMFont info.size），scale = 请求字号 / 名义字号。
     */
    int nominalFontSize();

    /**
     * 行高（像素，未缩放）。
     */
    int lineHeight();

    /**
     * 选定尺寸档位的视图：布局引擎在每次 layout 开头以
     * {@code 请求字号 / 名义字号} 为参数调用一次，随后全部字形查询作用在返回值上。
     * <p>
     * 位图实现无尺寸概念，返回 {@code this}；TTF 动态图集实现据此选定 size bucket
     * （结合有效屏幕缩放量化），后续栅格化按 bucket 的目标像素尺寸执行。
     * <p>
     * 线程前提：渲染调用只发生在逻辑线程，实现可持有可变的「当前 bucket」状态
     * 并直接返回 {@code this}；不允许在渲染线程调用。
     *
     * @param scale 请求字号 / 名义字号
     * @return 尺寸视图（可为 {@code this}）
     */
    default GlyphProvider forScale(final float scale) {
        return this;
    }

    /**
     * 布局完成后、发射前由调用方调用：把布局期间累积的待上传图集数据提交进
     * 渲染线程命令流。位图实现无待上传数据，空体。
     */
    default void flushPendingUploads() {
    }

    /**
     * 本来源的字形是否采样动态图集纹理（{@link GlyphMetrics#textureId()} 有效）：
     * true = 发射层按 quad 携带的 textureId 分组绑定；false = 位图路径，发射层
     * 绑定 pass 级字体纹理（quad 的 textureId 恒为 0）。
     */
    default boolean usesAtlasTexture() {
        return false;
    }
}
