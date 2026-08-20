package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.TerrainTileCompressionHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.zip.DataFormatException;

/**
 * 基础 tiled 地形压缩格式优化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.impl.campaign.terrain.BaseTiledTerrain}<br>
 * 注入动机：原版会先把 tile 状态按位打包，再用 {@link java.util.zip.Deflater} 压缩成 Base64 字符串；
 * 大地图存档下该压缩步骤是显著热点。<br>
 * 注入效果：保持位打包与存档字段形态不变，仅把压缩层改为“Zstd 新格式 + 旧 Deflater 读取回退”。
 */
@Mixin(targets = GameClassNames.BASE_TILED_TERRAIN_DOTTED)
public abstract class BaseTiledTerrainMixin {
    /**
     * 编码二态 tile 网格。
     *
     * @param tiles 原始 tile 网格
     * @return 压缩后的存档字符串
     * @author GitHub Copilot
     * @reason 保留原版位打包语义，仅替换更快的压缩层并加入兼容回退阀门。
     */
    @Overwrite(remap = false)
    public static String encodeTiles(final int[][] tiles) {
        return TerrainTileCompressionHelper.encodeBinaryTiles(tiles);
    }

    /**
     * 解码二态 tile 网格。
     *
     * @param encoded 压缩后的存档字符串
     * @param width   tile 宽度
     * @param height  tile 高度
     * @return 解码后的 tile 网格
     * @throws DataFormatException 当压缩字符串损坏时抛出
     * @author GitHub Copilot
     * @reason 优先读取新的 Zstd 前缀格式，同时完整兼容旧版 Deflater 存档内容。
     */
    @Overwrite(remap = false)
    public static int[][] decodeTiles(final String encoded,
                                      final int width,
                                      final int height) throws DataFormatException {
        return TerrainTileCompressionHelper.decodeBinaryTiles(encoded, width, height);
    }
}