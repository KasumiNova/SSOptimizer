package github.kasuminova.ssoptimizer.common.font.atlas;

/**
 * 图集中的一个字形槽位：页引用 + 像素坐标 + 预计算的归一化 UV + 画布原点。
 * textureId 直读页（上下文重建后 id 变化经页透传，槽位本体不变）。
 *
 * @param originX 画布原点（并集盒左缘）相对落笔点的设备像素偏移（笔坐标系）
 * @param originY 画布原点（并集盒顶缘）相对行顶的设备像素偏移（行顶坐标系）
 */
public record GlyphSlot(
        GlyphAtlasPage page,
        int x,
        int y,
        int width,
        int height,
        int originX,
        int originY,
        float texX,
        float texY,
        float texWidth,
        float texHeight) {

    /** 所在页的当前 GL 纹理 id（0 = 待重建，flush 后生效）。 */
    public int textureId() {
        return page.textureId();
    }
}
