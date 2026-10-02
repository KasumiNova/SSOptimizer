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
     * @param originU  图集区域原点 U（regionX / atlasSize），模组经 setTexX 等
     *                 访问器写入原始空间 UV 时的换算基准
     * @param originV  图集区域原点 V（regionY / atlasSize）
     * @param scaleU   原始空间→图集空间 U 缩放（srcW / atlasSize）
     * @param scaleV   原始空间→图集空间 V 缩放（srcH / atlasSize）
     */
    public record RemappedUv(float texX, float texY, float texWidth, float texHeight,
                             float insetU, float insetV,
                             float originU, float originV, float scaleU, float scaleV) {
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
        final float scaleU = srcW / atlasSize;
        final float scaleV = srcH / atlasSize;
        final float originU = (float) regionX / atlasSize;
        final float originV = (float) regionY / atlasSize;
        return new RemappedUv(
                originU + originX * scaleU,
                originV + originY * scaleV,
                originWidth * scaleU,
                originHeight * scaleV,
                0.001F * scaleU,
                0.001F * scaleV,
                originU, originV, scaleU, scaleV);
    }

    /**
     * 原始纹理空间 UV → 图集空间（模组经 {@code Sprite.setTexX/setTexWidth} 等访问器
     * 写入的是原始空间值——原版语义下 UV 域就是整张独立纹理——图集化后必须换算，
     * 否则写入值会以图集页为采样域直接串图）。
     * 宽度/高度等跨度量传 {@code origin = 0}。
     *
     * @param origin   图集区域原点（RemappedUv.originU/originV；跨度量传 0）
     * @param scale    原始→图集缩放（RemappedUv.scaleU/scaleV）
     * @param original 原始纹理空间值
     * @return 图集空间值
     */
    public static float originalToAtlas(final float origin, final float scale, final float original) {
        return origin + original * scale;
    }

    /**
     * 图集空间 UV → 原始纹理空间（{@code Sprite.getTexX/getTexWidth} 等读取器对
     * 模组保持原版语义——读到原始空间值，与 setter 换算对称，读写往返恒等）。
     * 宽度/高度等跨度量传 {@code origin = 0}。
     *
     * @param origin 图集区域原点（跨度量传 0）
     * @param scale  原始→图集缩放
     * @param atlas  图集空间值
     * @return 原始纹理空间值
     */
    public static float atlasToOriginal(final float origin, final float scale, final float atlas) {
        return (atlas - origin) / scale;
    }
}
