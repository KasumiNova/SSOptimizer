package github.kasuminova.ssoptimizer.common.font.layout;

/**
 * 支持栅格化层描边合成的字形来源（TTF 动态图集）。
 * <p>
 * 动机（设计文档 §4.5 定案）：原版边框/描边用「同一张位图 alpha 多偏移/缩放副本叠加」
 * 实现，副本错位采样产生结构性毛边；TTF 源可以按目标像素尺寸重栅格化，把描边在
 * 栅格化层用 FreeType Stroker 合成出「填充 ∪ 外扩描边」的剪影字形，发射时
 * 剪影 quad 垫底 + 填充 quad 盖顶，单 pass 完成。
 * <p>
 * 契约：剪影位图 = 填充字形位图盒向外各方向扩张 描边宽度（设备像素）后的区域，
 * 引擎发射剪影 quad 时按逻辑描边宽度等比扩张填充几何并整图采样该槽位。
 */
public interface OutlineGlyphProvider extends GlyphProvider {

    /**
     * 是否启用描边合成（false 时布局引擎回退原版多 pass 路径）。
     */
    boolean synthesizesOutline();

    /**
     * 查描边剪影字形：UV 与 textureId 指向描边图集槽位，
     * 排版度量（xOffset/xAdvance/bearingY/width/height）与同尺寸填充字形一致。
     *
     * @param codePoint            字符码点
     * @param strokeWidthLogicalPx 描边宽度（逻辑像素，与字号请求同一坐标系；
     *                             实现内部换算为设备像素并按 0.5px 步进量化入缓存 key）
     * @return 剪影字形度量；栅格化失败返回 {@code null}（引擎按缺失字形语义处理）
     */
    GlyphMetrics strokedGlyph(int codePoint, float strokeWidthLogicalPx);

    /**
     * 当前尺寸档位的 bucket 缩放（最近一次 {@link #forScale(float)} 选定，
     * 含有效屏幕缩放）：引擎把阴影偏移换算到屏幕像素空间取整时需要它。
     */
    float currentBucketScale();
}
