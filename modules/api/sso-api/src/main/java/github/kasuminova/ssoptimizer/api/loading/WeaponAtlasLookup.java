package github.kasuminova.ssoptimizer.api.loading;

/**
 * 按原始资源路径查询舰船或武器贴图在图集中的区域，供渲染模块重定向纹理绑定与 UV。
 * <p>
 * 查询无副作用且位于逐帧路径，实现须支持渲染线程高频调用，不得阻塞或触发资源加载。
 * 未注册 = 图集查询不可用；可选渲染路径应保留原始纹理与 UV，强制依赖图集的路径应报告服务缺失。
 */
public interface WeaponAtlasLookup {

    /**
     * 描述一个不可变的图集区域，提供纹理绑定和 UV 重映射所需参数。
     *
     * @param textureId 图集页的 GL 纹理 ID
     * @param atlasSize 正方形图集页的边长，单位为像素
     * @param x         区域左下角在图集页内的 X 坐标，单位为像素
     * @param y         区域左下角在图集页内的 Y 坐标，单位为像素
     */
    record Region(int textureId, int atlasSize, int x, int y) {
    }

    /**
     * 查询原始贴图路径对应的图集区域，不修改贴图或图集状态。
     *
     * @param texturePath 原始贴图的游戏资源路径
     * @return 已入图集时返回对应区域；未入图集或图集尚未包含该路径时返回 {@code null}
     */
    Region lookupRegion(String texturePath);
}
