package github.kasuminova.ssoptimizer.common.render.atlas;

import com.fs.graphics.TextureObject;
import github.kasuminova.ssoptimizer.api.loading.WeaponAtlasLookup;
import github.kasuminova.ssoptimizer.bootstrap.ServiceRegistry;

/**
 * 精灵渲染路径的纹理 id 图集解析。
 * <p>
 * 动机：ShipWeaponAtlas 图集化后，Sprite 渲染（SpriteMixin 覆写方法）消费的 UV
 * 已由 SpriteAtlasMixin 重映射进图集空间，绑定必须使用图集页 id；而
 * {@code TextureObject.getTextureId()} 的其余调用方（模组经 SpriteAPI 取 id 后
 * 以 0..1 裸 UV 全图采样，如 BoxUtil MaterialData 默认漫反射槽、Polaris
 * PLSP_BoxBasedUtil.genSDF）必须拿到独立纹理 id，否则采到整页图集平铺。
 * 图集注入因此收敛到 Sprite 渲染方法本身——本解析器是唯一感知图集的
 * getTextureId 替代入口；{@code LazyTextureManager.getTextureId} 不感知图集，
 * 始终保持惰性上传 + 独立纹理的原版语义。
 */
public final class AtlasTextureResolver {
    /** 图集查询服务（coremod 装配期注册，注册先于任何精灵渲染；实例稳定，解析一次后缓存）。 */
    private static volatile WeaponAtlasLookup lookup;

    private AtlasTextureResolver() {
    }

    /**
     * 解析精灵渲染用的纹理 id：贴图已入图集返回图集页 id（配套重映射 UV 采样），
     * 否则委托 {@link TextureObject#getTextureId()}（惰性上传独立纹理，原版语义）。
     */
    public static int textureIdForSpriteRender(final TextureObject texture) {
        return textureIdForSpriteRender(texture, atlasLookup());
    }

    /** 实现本体（查询接口可注入，供单测直接验证完整逻辑）。 */
    public static int textureIdForSpriteRender(final TextureObject texture,
                                               final WeaponAtlasLookup atlasLookup) {
        final WeaponAtlasLookup.Region region = atlasLookup.lookupRegion(texture.getTexturePath());
        if (region != null) {
            return region.textureId();
        }
        return texture.getTextureId();
    }

    private static WeaponAtlasLookup atlasLookup() {
        WeaponAtlasLookup current = lookup;
        if (current == null) {
            current = ServiceRegistry.require(WeaponAtlasLookup.class);
            lookup = current;
        }
        return current;
    }
}
