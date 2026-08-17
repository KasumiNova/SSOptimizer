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
}
