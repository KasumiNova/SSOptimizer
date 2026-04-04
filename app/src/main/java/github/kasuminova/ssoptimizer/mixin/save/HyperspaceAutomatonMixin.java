package github.kasuminova.ssoptimizer.mixin.save;

import github.kasuminova.ssoptimizer.common.save.TerrainTileCompressionHelper;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.zip.DataFormatException;

/**
 * 超空间 automaton tile 压缩格式优化 Mixin。
 * <p>
 * 注入目标：{@code com.fs.starfarer.api.impl.campaign.terrain.HyperspaceAutomaton}<br>
 * 注入动机：该类保存超空间网格时会把四态 tile 压成 2 bit 后再走 {@link java.util.zip.Deflater}；
 * 在热点报告里这是最刺眼的保存 CPU 消耗之一。<br>
 * 注入效果：保持原始 2 bit 编码与读回语义，只把压缩层升级为“Zstd 新格式 + 旧 Deflater 兼容解码”。
 */
@Mixin(targets = GameClassNames.HYPERSPACE_AUTOMATON_DOTTED)
public abstract class HyperspaceAutomatonMixin {
    /**
     * 编码四态超空间 tile 网格。
     *
     * @param tiles 原始 tile 网格
     * @return 压缩后的存档字符串
     * @author GitHub Copilot
     * @reason 保留原版 2 bit 打包布局，只替换为更快的压缩层。
     */
    @Overwrite(remap = false)
    public static String encodeTiles(final int[][] tiles) {
        return TerrainTileCompressionHelper.encodeQuaternaryTiles(tiles);
    }

    /**
     * 解码四态超空间 tile 网格。
     *
     * @param encoded 压缩后的存档字符串
     * @param width   tile 宽度
     * @param height  tile 高度
     * @return 解码后的 tile 网格
     * @throws DataFormatException 当压缩字符串损坏时抛出
     * @author GitHub Copilot
     * @reason 统一兼容新的 Zstd 格式与旧版 Deflater 格式，避免新老存档互相读不回。
     */
    @Overwrite(remap = false)
    public static int[][] decodeTiles(final String encoded,
                                      final int width,
                                      final int height) throws DataFormatException {
        return TerrainTileCompressionHelper.decodeQuaternaryTiles(encoded, width, height);
    }
}