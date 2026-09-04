package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import github.kasuminova.ssoptimizer.common.render.campaign.TerrainTileRandomCache;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * 超空间地形（HyperspaceTerrainPlugin）性能优化 Mixin，当前覆盖 A2
 * （renderQuad 逐瓦片随机构造缓存）一项。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin}。
 * <p>
 * <b>A2 — renderQuad 随机构造缓存（named 源码 :266-297）</b>：每瓦片每层
 * {@code new Random(seed)} + 21 次 nextFloat（angle + 4 组 theta/radius 随机
 * 游走初值），seed 由瓦片渲染坐标确定性派生。构造重定向到
 * {@link TerrainTileRandomCache}（按 seed 缓存、从头重放预取序列，位级等价）；
 * theta/radius 的时间推进（elapsed）与 fader/signal 等动态量仍由原版代码
 * 逐帧计算，动画行为不变。
 * <p>
 * 曾实施的 A1（advance 双循环 delta 化）因门控以 interval 粒度步进
 * {@code curr.advance(days)} 的时间驱动动画，导致风暴闪烁跳变，已整体回退：
 * subgrid 更新循环必须每帧执行，而仅门控清理循环的收益（每帧约 1.2 万次
 * 边界判定 + 环带置空）为噪声级，不抵 @Overwrite 的维护成本。
 */
@Mixin(HyperspaceTerrainPlugin.class)
public abstract class HyperspaceTerrainPluginMixin {
    /** A2：renderQuad 瓦片随机序列缓存（transient，读档后懒重建）。 */
    @Unique
    private transient TerrainTileRandomCache ssoptimizer$renderQuadRandomCache;

    @Redirect(
            method = "renderQuad",
            at = @At(value = "NEW", target = GameMixinSignatures.HyperspaceTerrainPlugin.TILE_RANDOM_NEW),
            remap = false)
    private Random ssoptimizer$cachedTileRandom(final long seed) {
        if (ssoptimizer$renderQuadRandomCache == null) {
            ssoptimizer$renderQuadRandomCache = new TerrainTileRandomCache();
        }
        return ssoptimizer$renderQuadRandomCache.random(seed);
    }
}
