package github.kasuminova.ssoptimizer.api.loading;

/**
 * 舰船/武器贴图图集查询：按原始贴图路径查询图集化后的区域。
 * <p>
 * 动机：loading 域的 {@code ShipWeaponAtlas} 把舰船/武器贴图合并进图集，
 * render 域的 Sprite Mixin 需要在纹理绑定时查询重映射区域——跨域行为调用
 * 经本接口，render 不直接依赖 loading。
 * <p>
 * 实现由 loading 域提供（桥接 ShipWeaponAtlas），在 coremod 装配期经
 * {@code ServiceRegistry} 注册。调用频率为纹理绑定/换绑级，非逐帧热点。
 */
public interface WeaponAtlasLookup {

    /**
     * 图集区域：目标纹理与 UV 重映射所需的全部参数。
     *
     * @param textureId 图集页的 GL 纹理 ID
     * @param atlasSize 图集页边长（像素，正方形页）
     * @param x         区域在图集页内的左下角 X（像素）
     * @param y         区域在图集页内的左下角 Y（像素）
     */
    record Region(int textureId, int atlasSize, int x, int y) {
    }

    /**
     * 查询贴图路径对应的图集区域。
     *
     * @param texturePath 原始贴图路径（游戏资源路径）
     * @return 图集区域；该贴图未参与图集化时返回 {@code null}
     */
    Region lookupRegion(String texturePath);
}
