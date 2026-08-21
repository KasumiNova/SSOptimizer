package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 单个字形的排版度量与图集 UV（BMFont 语义）。
 * <p>
 * 动机：布局引擎（{@link TextLayoutEngine}）需要脱离游戏类工作以便纯单测，
 * 本 record 是从游戏 BitmapGlyph / TTF 动态图集槽位抽象出的最小度量面。
 * 坐标系约定与原版一致：bearingY 为行顶到字形盒顶的距离（像素，fnt yoffset 原值），
 * tex 坐标为归一化 UV。
 * <p>
 * 位图路径各度量恒为整数（fnt 原值）；TTF 动态图集路径为「并集画布」语义：
 * 槽位画布取「fnt 盒 × bucketScale」与实际墨迹包围盒的并集（FreeType hinting
 * 在非整数缩放下会超出缩放 fnt 盒 1-2 设备像素，硬裁剪会丢失边缘像素），
 * xOffset/bearingY/width/height 随之携带亚像素值（设备像素 ÷ bucketScale），
 * xAdvance 补偿左溢出量以保持 xOffset+xAdvance 步进和与 fnt 原值一致。
 *
 * @param xOffset   落笔点前移量（逻辑像素，未缩放；TTF 路径 = 画布原点 ÷ bucketScale）
 * @param xAdvance  字形步进（逻辑像素，未缩放；TTF 路径含左溢出补偿）
 * @param bearingY  行顶到字形盒顶的距离（逻辑像素，未缩放；位图路径为 fnt yoffset 原值——
 *                  游戏解析器（BitmapFontManager）把 yoffset 原样装入 bearingY，
 *                  与 native 栅格化的 yOffset（baseline − bitmap_top）同为行顶相对坐标）
 * @param width     字形盒宽（逻辑像素）
 * @param height    字形盒高（逻辑像素）
 * @param texX      图集 UV 左
 * @param texY      图集 UV 上
 * @param texWidth  图集 UV 宽
 * @param texHeight 图集 UV 高
 * @param textureId 字形所在图集页的 GL 纹理 id；0 = 位图路径（发射时使用
 *                  pass 级绑定的字体纹理，quad 不携带纹理语义）
 */
public record GlyphMetrics(
        float xOffset,
        float xAdvance,
        float bearingY,
        float width,
        float height,
        float texX,
        float texY,
        float texWidth,
        float texHeight,
        int textureId) {
}
