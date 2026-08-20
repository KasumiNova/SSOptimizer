package github.kasuminova.ssoptimizer.savebench;

import github.kasuminova.ssoptimizer.common.save.TerrainTileCompressionHelper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;

/**
 * 离线基准的存档 payload 格式归一化。
 *
 * <p>SSOptimizer 游戏内通过 Mixin 把 {@code BaseTiledTerrain}/{@code HyperspaceAutomaton}
 * 的 tile 压缩格式扩展为 {@code SSOZ1:} + Zstd（见
 * {@link TerrainTileCompressionHelper}）。离线基准不应用任何 Mixin，游戏类
 * {@code readResolve} 走原版解码路径，无法识别新前缀；且 classpath 上 jaxb-api
 * 内嵌 {@code DatatypeConverterImpl.guessLength} 对非四字节对齐的拼接 base64
 * 存在低估缺陷，会直接 {@code ArrayIndexOutOfBoundsException}。</p>
 *
 * <p>因此离线 unmarshal 前先把存档文本中所有 {@code SSOZ1:} 字段值回译为旧版
 * Deflater 分块格式（语义等价，仅压缩层不同）。转换产物写入临时文件，
 * 不修改原始存档。</p>
 */
public final class SavePayloadNormalizer {
    private static final Pattern ZSTD_FIELD = Pattern.compile(">SSOZ1:([A-Za-z0-9+/=]+)<");

    private SavePayloadNormalizer() {
    }

    /**
     * 把存档主档中全部 {@code SSOZ1:} 字段值回译为旧版格式，写入临时文件。
     *
     * @param source 原始 campaign.xml
     * @return 归一化后的临时文件路径；无 {@code SSOZ1:} 字段时直接返回原路径
     */
    public static Path normalizeToLegacyFile(final Path source) throws IOException {
        final String xml = Files.readString(source, StandardCharsets.UTF_8);
        if (!xml.contains(">SSOZ1:")) {
            return source;
        }

        final Matcher matcher = ZSTD_FIELD.matcher(xml);
        final StringBuilder out = new StringBuilder(xml.length());
        int converted = 0;
        while (matcher.find()) {
            final String legacy;
            try {
                legacy = TerrainTileCompressionHelper.toLegacyDeflateFormat("SSOZ1:" + matcher.group(1));
            } catch (final DataFormatException e) {
                throw new IOException("SSOZ1 字段回译失败（offset " + matcher.start() + "）", e);
            }
            matcher.appendReplacement(out, ">" + Matcher.quoteReplacement(legacy) + "<");
            converted++;
        }
        matcher.appendTail(out);

        try {
            final Path normalized = Files.createTempFile("savebench-normalized-", ".xml");
            Files.writeString(normalized, out, StandardCharsets.UTF_8);
            normalized.toFile().deleteOnExit();
            org.apache.log4j.Logger.getLogger(SavePayloadNormalizer.class)
                    .info("[SaveBench] SSOZ1 fields normalized to legacy deflate: " + converted);
            return normalized;
        } catch (final IOException e) {
            throw new UncheckedIOException("写入归一化临时文件失败", e);
        }
    }
}
