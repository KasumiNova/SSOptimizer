package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 单个字形的排版度量与图集 UV（BMFont 语义）。
 * <p>
 * 动机：布局引擎（{@link TextLayoutEngine}）需要脱离游戏类工作以便纯单测，
 * 本 record 是从游戏 BitmapGlyph / 未来 TTF 动态图集槽位抽象出的最小度量面。
 * 坐标系约定与原版一致：bearingY 为基线到字形顶的距离（像素），
 * tex 坐标为归一化 UV。
 *
 * @param xOffset   落笔点前移量（像素，未缩放）
 * @param xAdvance  字形步进（像素，未缩放）
 * @param bearingY  基线到字形顶距离（像素，未缩放）
 * @param width     字形像素宽
 * @param height    字形像素高
 * @param texX      图集 UV 左
 * @param texY      图集 UV 上
 * @param texWidth  图集 UV 宽
 * @param texHeight 图集 UV 高
 */
public record GlyphMetrics(
        int xOffset,
        int xAdvance,
        int bearingY,
        int width,
        int height,
        float texX,
        float texY,
        float texWidth,
        float texHeight) {
}
