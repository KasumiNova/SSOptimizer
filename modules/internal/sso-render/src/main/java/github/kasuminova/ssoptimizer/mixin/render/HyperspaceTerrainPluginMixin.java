package github.kasuminova.ssoptimizer.mixin.render;

import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import github.kasuminova.ssoptimizer.common.render.campaign.TerrainTileRandomCache;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Random;

@Mixin(HyperspaceTerrainPlugin.class)
public abstract class HyperspaceTerrainPluginMixin {
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
