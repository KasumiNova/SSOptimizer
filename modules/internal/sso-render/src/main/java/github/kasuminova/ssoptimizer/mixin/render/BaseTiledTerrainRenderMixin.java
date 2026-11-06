package github.kasuminova.ssoptimizer.mixin.render;

import github.kasuminova.ssoptimizer.common.render.campaign.TerrainTileRandomCache;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

@Mixin(targets = GameMixinSignatures.BaseTiledTerrain.TARGET_CLASS)
public abstract class BaseTiledTerrainRenderMixin {
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
