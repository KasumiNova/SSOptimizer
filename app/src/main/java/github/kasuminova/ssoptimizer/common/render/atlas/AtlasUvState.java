package github.kasuminova.ssoptimizer.common.render.atlas;

/**
 * Sprite 图集 UV 重映射状态查询接口（Mixin 接口注入到 {@code com.fs.graphics.Sprite}）。
 * <p>
 * 动机：{@code SpriteMixin} 覆写 {@code renderRegion} 时需要知道当前精灵的 UV 是否已
 * 重映射进图集（决定 UV 是否补区域原点偏移、边缘内缩取图集像素等价值还是原版 0.001F），
 * 但 Mixin 包不被 LaunchClassLoader 加载，跨 Mixin 直接强转会抛 NoClassDefFoundError；
 * 通过本接口（位于普通包）做 Mixin 间状态传递是标准解法。
 * <p>
 * 实现：{@code SpriteAtlasMixin} implements 本接口，重映射在
 * {@code setTexture}/{@code readResolve} 尾部完成并缓存内缩值。
 */
public interface AtlasUvState {
    /**
     * 查询当前精灵 UV 是否已重映射进图集。
     *
     * @return true 表示 texX/texY/texWidth/texHeight 已是图集 GL 空间
     */
    boolean ssoptimizer$isAtlasRemapped();

    /**
     * 图集 UV 域的 U 向内缩量（与原版 renderRegion 的 0.001F 像素等价）。
     * 仅在 {@link #ssoptimizer$isAtlasRemapped()} 为 true 时有意义。
     *
     * @return U 向内缩量
     */
    float ssoptimizer$atlasInsetU();

    /**
     * 图集 UV 域的 V 向内缩量（与原版 renderRegion 的 0.001F 像素等价）。
     * 仅在 {@link #ssoptimizer$isAtlasRemapped()} 为 true 时有意义。
     *
     * @return V 向内缩量
     */
    float ssoptimizer$atlasInsetV();

    /**
     * 重映射时缓存的图集页 GL 纹理 id（供合批提交使用）。
     * <p>
     * 图集双轨制下 {@code TextureObject.getTextureId()} 只返回原始纹理 id
     * （raw id 消费方需要原始 UV 空间的真实纹理），合批路径必须改用本值
     * 才能与同页精灵共享图集纹理。
     *
     * @return 图集页纹理 id；未重映射时返回 -1
     */
    int ssoptimizer$atlasTextureId();
}
