package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 一个已烘焙好顶点坐标、UV 与颜色的文本四边形。
 * <p>
 * 顶点顺序与原版 {@code BitmapFontRenderer.drawGlyph} 的逐顶点序列一致：
 * 1=左上(texX,texY+texH) 2=左下(texX,texY) 3=右下(texX+texW,texY) 4=右上(texX+texW,texY+texH)
 * （阴影放大副本等变体的 UV 角点对应关系相同）。
 * 颜色为 ARGB 打包 int（a<<24|r<<16|g<<8|b），已在布局期按 pass/选区规则烘焙。
 * textureId 为字形所在图集页的 GL 纹理 id（0 = 位图路径，发射层用 pass 级纹理）；
 * 图集路径下发射层按 textureId 连续分组切段绑定。
 */
public record GlyphQuad(
        float x1, float y1, float u1, float v1,
        float x2, float y2, float u2, float v2,
        float x3, float y3, float u3, float v3,
        float x4, float y4, float u4, float v4,
        int color,
        int textureId) {
}
