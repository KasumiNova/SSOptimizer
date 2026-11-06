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

/** 离线读档前回译 SSOZ1 字段，避免原版解码与 JAXB 拼接 Base64 长度缺陷。 */
public final class SavePayloadNormalizer {
    private static final Pattern ZSTD_FIELD = Pattern.compile(">SSOZ1:([A-Za-z0-9+/=]+)<");

    private SavePayloadNormalizer() {
    }

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
