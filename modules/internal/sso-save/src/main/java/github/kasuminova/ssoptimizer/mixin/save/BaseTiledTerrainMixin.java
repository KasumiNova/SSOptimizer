package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.TerrainTileCompressionHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.zip.DataFormatException;

@Mixin(targets = GameClassNames.BASE_TILED_TERRAIN_DOTTED)
public abstract class BaseTiledTerrainMixin {
    @Overwrite(remap = false)
    public static String encodeTiles(final int[][] tiles) {
        return TerrainTileCompressionHelper.encodeBinaryTiles(tiles);
    }

    @Overwrite(remap = false)
    public static int[][] decodeTiles(final String encoded,
                                      final int width,
                                      final int height) throws DataFormatException {
        return TerrainTileCompressionHelper.decodeBinaryTiles(encoded, width, height);
    }
}