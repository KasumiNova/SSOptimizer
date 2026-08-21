package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 字形度量与 kerning 的查询来源。
 * <p>
 * 动机：{@link TextLayoutEngine} 不直接依赖游戏 BitmapFont 类，
 * 经本接口查询字形数据——位图字体（原版 fnt）与 P3 的 TTF 动态图集
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
}
