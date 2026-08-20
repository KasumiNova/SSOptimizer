package github.kasuminova.ssoptimizer.common.render.atlas;

/**
 * Sprite 图集 UV 重映射的纯计算（无游戏类依赖，供 {@code SpriteAtlasMixin} 与单测共用）。
 * <p>
 * 动机：舰船/武器贴图合并进图集后，Sprite 的纹理坐标仍指向原始独立纹理的 UV
 * 空间，必须换算进图集区域（与绑定层的图集重定向配套）。换算公式与重映射的
 * 幂等语义（见 {@link #remapFromOrigin} 的 javadoc）是 Mixin 注入点与单元测试
 * 共同的核心逻辑，抽取为无副作用静态方法：Mixin 的 setTexture/readResolve
 * 注入点与「同贴图重复 setTexture 的幂等重推导」共用同一实现，测试可直接
 * 验证公式与幂等性而不必构造游戏 Sprite。
 */
public final class AtlasUvMapper {

    private AtlasUvMapper() {
    }

    /**
     * 图集 UV 重映射的纯计算结果。
     *
     * @param texX     图集 GL 空间 U 原点
     * @param texY     图集 GL 空间 V 原点
     * @param texWidth 图集 GL 空间 U 宽度
     * @param texHeight 图集 GL 空间 V 高度
     * @param insetU   图集 UV 域 U 向内缩量（与原版 renderRegion 的 0.001F 像素等价）
     * @param insetV   图集 UV 域 V 向内缩量
     */
    public record RemappedUv(float texX, float texY, float texWidth, float texHeight,
                             float insetU, float insetV) {
    }

    /**
     * 从原始纹理 UV 四元组推导图集 UV 四元组与边缘内缩。
     * <p>
     * 换算：{@code texX' = (regionX + texX * srcW) / atlasSize}（Y/宽/高同理，
     * srcW/srcH 为原纹理 GL 尺寸，由 imageWidth/uScale 推得）；内缩按
     * 原版 renderRegion 的 0.001F 像素基准换算到图集 UV 域（像素等价）。
     * <p>
     * <b>幂等性契约</b>：本方法以「原始纹理空间」的 UV 为输入。若把图集空间
     * 的输出值再次当原始值传入（修复前 {@code setTexture} 在叠加后的当前值上
     * 再次换算），结果会再平移一次进入相邻图集 Region——串图根因。调用方
     * （{@code SpriteAtlasMixin}）必须缓存首次换算时的原始四元组，同贴图重复
     * setTexture 时从缓存原始值重新推导（结果与首次一致，幂等）。
     *
     * @param originX     原始纹理 GL 空间 U 原点
     * @param originY     原始纹理 GL 空间 V 原点
     * @param originWidth 原始纹理 GL 空间 U 宽度
     * @param originHeight 原始纹理 GL 空间 V 高度
     * @param srcW        原纹理 GL 尺寸宽（imageWidth / uScale）
     * @param srcH        原纹理 GL 尺寸高（imageHeight / vScale）
     * @param regionX     图集区域左下角 X（像素）
     * @param regionY     图集区域左下角 Y（像素）
     * @param atlasSize   图集页边长（像素）
     */
    public static RemappedUv remapFromOrigin(
            final float originX, final float originY,
            final float originWidth, final float originHeight,
            final float srcW, final float srcH,
            final int regionX, final int regionY, final int atlasSize) {
        return new RemappedUv(
                (regionX + originX * srcW) / atlasSize,
                (regionY + originY * srcH) / atlasSize,
                originWidth * srcW / atlasSize,
                originHeight * srcH / atlasSize,
                0.001F * srcW / atlasSize,
                0.001F * srcH / atlasSize);
    }
}
