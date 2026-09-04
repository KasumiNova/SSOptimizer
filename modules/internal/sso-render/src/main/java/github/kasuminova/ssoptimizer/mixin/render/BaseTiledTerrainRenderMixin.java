package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.campaign.TerrainTileRandomCache;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

/**
 * 基础瓦片地形 {@code renderSubArea} 逐瓦片随机构造优化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain#renderSubArea}
 * （named 源码 :402-531）。<br>
 * 注入动机：samples==1 路径（:420）与 samples>1 无 sampleCache 路径（:489）对每块
 * 可见瓦片每帧执行 {@code new Random(seed)} + 3 次 nextFloat，星域大图视口内数百
 * 瓦片 → 每帧数千次 Random 构造（种子搅拌 + AtomicLong CAS 序列）。瓦片随机参数
 * 由 {@code (i, j, tiles.length)} 派生的 seed 完全确定，属纯重复计算。<br>
 * 注入效果：两处 {@code new Random(long)} 构造重定向到
 * {@link TerrainTileRandomCache}（按 seed 懒生成、逐帧复用同一实例从头重放
 * 预取序列），输出与原版位级一致（等价性论证见
 * {@link TerrainTileRandomCache} javadoc）；xOff/yOff 的 offRange 缩放、angle 的
 * ×360 等后续运算仍由原版代码逐帧执行，不受影响。
 */
@Mixin(targets = GameMixinSignatures.BaseTiledTerrain.TARGET_CLASS)
public abstract class BaseTiledTerrainRenderMixin {
    /**
     * 运行期派生缓存，不随存档持久化（transient）；读档后为 null，首次渲染经
     * 下方 null 守卫懒重建。注意 {@code HyperspaceTerrainPlugin} 会同时应用本
     * Mixin 与 {@code HyperspaceTerrainPluginMixin}，两实例缓存各自独立。
     */
    @Unique
    private transient TerrainTileRandomCache ssoptimizer$subAreaRandomCache;

    @Redirect(
            method = "renderSubArea",
            at = @At(value = "NEW", target = GameMixinSignatures.BaseTiledTerrain.TILE_RANDOM_NEW),
            remap = false)
    private Random ssoptimizer$cachedTileRandom(final long seed) {
        if (ssoptimizer$subAreaRandomCache == null) {
            ssoptimizer$subAreaRandomCache = new TerrainTileRandomCache();
        }
        return ssoptimizer$subAreaRandomCache.random(seed);
    }
}
