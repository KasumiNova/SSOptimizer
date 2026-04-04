package github.kasuminova.ssoptimizer.common.save;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;
import javax.xml.bind.DatatypeConverter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * 战役地形 tile 压缩辅助类。
 * <p>
 * 职责：复用原版 {@code BaseTiledTerrain}/{@code HyperspaceAutomaton} 的位打包语义，
 * 仅把最终压缩层从默认 {@link Deflater} 扩展为“Zstd 优先、旧 Deflater 回退”的双格式实现。<br>
 * 设计动机：热点显示大存档保存时，超空间与基础 tiled 地形的 {@code encodeTiles()} 在 Deflater 上消耗稳定 CPU；
 * 这些方法本质上只是把已经位打包好的小字节流压缩后再 Base64 编码，适合改为更快的 Zstd。<br>
 * 兼容性策略：新格式统一写成带前缀的 Base64 文本，读取时优先识别新前缀；若没有前缀，则完整回退到原版
 * Deflater/Inflater 路径，保证旧存档仍可读取；同时保留系统属性开关以便紧急退回旧写入格式。
 */
public final class TerrainTileCompressionHelper {
    /**
     * 禁用地形 tile Zstd 写入格式的系统属性。
     */
    public static final String DISABLE_ZSTD_PROPERTY = "ssoptimizer.disable.save.terrain.zstd";

    private static final String ZSTD_PREFIX = "SSOZ1:";
    private static final int    IO_BUFFER_SIZE = 256;
    private static final int    LEGACY_DEFLATE_CHUNK_BYTES = 100;

    private TerrainTileCompressionHelper() {
    }

    /**
     * 编码仅含“有 / 无”两态的地形 tile 数组。
     *
     * @param tiles 原始 tile 网格；外层索引为 x，内层索引为 y
     * @return 可直接写入存档字段的压缩字符串
     */
    public static String encodeBinaryTiles(final int[][] tiles) {
        return encodePackedPayload(packBinaryTiles(tiles));
    }

    /**
     * 解码仅含“有 / 无”两态的地形 tile 数组。
     *
     * @param encoded 压缩字符串
     * @param width   tile 网格宽度（x 轴）
     * @param height  tile 网格高度（y 轴）
     * @return 解码后的 tile 网格
     * @throws DataFormatException 当压缩内容损坏、被截断或格式非法时抛出
     */
    public static int[][] decodeBinaryTiles(final String encoded,
                                            final int width,
                                            final int height) throws DataFormatException {
        return unpackBinaryTiles(decodePackedPayload(encoded), width, height);
    }

    /**
     * 编码四态的超空间 tile 数组。
     *
     * @param tiles 原始 tile 网格；外层索引为 x，内层索引为 y
     * @return 可直接写入存档字段的压缩字符串
     */
    public static String encodeQuaternaryTiles(final int[][] tiles) {
        return encodePackedPayload(packQuaternaryTiles(tiles));
    }

    /**
     * 解码四态的超空间 tile 数组。
     *
     * @param encoded 压缩字符串
     * @param width   tile 网格宽度（x 轴）
     * @param height  tile 网格高度（y 轴）
     * @return 解码后的 tile 网格
     * @throws DataFormatException 当压缩内容损坏、被截断或格式非法时抛出
     */
    public static int[][] decodeQuaternaryTiles(final String encoded,
                                                final int width,
                                                final int height) throws DataFormatException {
        return unpackQuaternaryTiles(decodePackedPayload(encoded), width, height);
    }

    private static byte[] packBinaryTiles(final int[][] tiles) {
        final int width = tiles.length;
        final int height = tiles[0].length;
        final int tileCount = width * height;
        final byte[] packed = new byte[(tileCount + 7) >>> 3];

        for (int index = 0; index < tileCount; index++) {
            final int x = index % width;
            final int y = index / width;
            if (tiles[x][y] >= 0) {
                final int byteIndex = index >>> 3;
                final int shift = 7 - (index & 7);
                packed[byteIndex] = (byte) (packed[byteIndex] | (1 << shift));
            }
        }

        return packed;
    }

    private static int[][] unpackBinaryTiles(final byte[] packed,
                                             final int width,
                                             final int height) throws DataFormatException {
        final int tileCount = width * height;

        final int[][] tiles = new int[width][height];
        int tileIndex = 0;
        for (final byte currentByte : packed) {
            for (int shift = 7; shift >= 0; shift--) {
                final int x = tileIndex % width;
                final int y = tileIndex / width;
                tileIndex++;
                if (tileIndex > tileCount) {
                    return tiles;
                }

                final int value = (Byte.toUnsignedInt(currentByte) >>> shift) & 1;
                tiles[x][y] = value != 0 ? 1 : -1;
            }
        }

        return tiles;
    }

    private static byte[] packQuaternaryTiles(final int[][] tiles) {
        final int width = tiles.length;
        final int height = tiles[0].length;
        final int tileCount = width * height;
        final byte[] packed = new byte[(tileCount + 3) >>> 2];

        for (int index = 0; index < tileCount; index++) {
            final int x = index % width;
            final int y = index / width;
            final int value = tiles[x][y];
            if (value >= 0) {
                final int byteIndex = index >>> 2;
                final int shift = 6 - ((index & 3) << 1);
                packed[byteIndex] = (byte) (packed[byteIndex] | ((value & 0x3) << shift));
            }
        }

        return packed;
    }

    private static int[][] unpackQuaternaryTiles(final byte[] packed,
                                                 final int width,
                                                 final int height) throws DataFormatException {
        final int tileCount = width * height;

        final int[][] tiles = new int[width][height];
        int tileIndex = 0;
        for (final byte currentByte : packed) {
            for (int shift = 3; shift >= 0; shift--) {
                final int x = tileIndex % width;
                final int y = tileIndex / width;
                tileIndex++;
                if (tileIndex > tileCount) {
                    return tiles;
                }

                tiles[x][y] = (Byte.toUnsignedInt(currentByte) >>> (shift * 2)) & 0x3;
            }
        }

        return tiles;
    }

    private static String encodePackedPayload(final byte[] packed) {
        if (Boolean.getBoolean(DISABLE_ZSTD_PROPERTY)) {
            return encodeLegacyDeflaterPayload(packed);
        }

        final byte[] compressed = compressWithZstd(packed);
        return ZSTD_PREFIX + Base64.getEncoder().encodeToString(compressed);
    }

    private static byte[] decodePackedPayload(final String encoded) throws DataFormatException {
        if (encoded == null) {
            throw dataFormat("地形 tile 字符串为空", null);
        }

        if (encoded.startsWith(ZSTD_PREFIX)) {
            final byte[] compressed = decodeBase64(encoded.substring(ZSTD_PREFIX.length()));
            return decompressWithZstd(compressed);
        }

        return decompressWithDeflater(decodeLegacyBase64Payload(encoded));
    }

    private static String encodeLegacyDeflaterPayload(final byte[] payload) {
        final Deflater deflater = new Deflater();
        deflater.setInput(payload);
        deflater.finish();

        final StringBuilder encoded = new StringBuilder(Math.max(32, payload.length));
        final byte[] buffer = new byte[LEGACY_DEFLATE_CHUNK_BYTES];
        try {
            while (!deflater.finished()) {
                final int written = deflater.deflate(buffer);
                if (written <= 0) {
                    throw new IllegalStateException("Deflater 未产出任何字节但也未结束");
                }
                encoded.append(DatatypeConverter.printBase64Binary(Arrays.copyOf(buffer, written)));
            }
            return encoded.toString();
        } finally {
            deflater.end();
        }
    }

    private static byte[] compressWithZstd(final byte[] payload) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(32, payload.length));
             ZstdOutputStream zstd = new ZstdOutputStream(output)) {
            zstd.write(payload);
            zstd.flush();
            zstd.close();
            return output.toByteArray();
        } catch (final IOException exception) {
            throw new IllegalStateException("Zstd 压缩地形 tile 数据失败", exception);
        }
    }

    private static byte[] decompressWithZstd(final byte[] compressed) throws DataFormatException {
        try (ByteArrayInputStream input = new ByteArrayInputStream(compressed);
             ZstdInputStream zstd = new ZstdInputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(IO_BUFFER_SIZE, compressed.length * 2))) {
            final byte[] buffer = new byte[IO_BUFFER_SIZE];
            int read;
            while ((read = zstd.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (final IOException exception) {
            throw dataFormat("Zstd 地形 tile 数据解压失败", exception);
        }
    }

    private static byte[] decompressWithDeflater(final byte[] compressed) throws DataFormatException {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);

        try (ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(IO_BUFFER_SIZE, compressed.length * 2))) {
            final byte[] buffer = new byte[IO_BUFFER_SIZE];
            while (!inflater.finished()) {
                final int read = inflater.inflate(buffer);
                if (read > 0) {
                    output.write(buffer, 0, read);
                    continue;
                }
                if (inflater.needsDictionary()) {
                    throw dataFormat("旧版地形 tile 数据要求字典", null);
                }
                if (inflater.needsInput()) {
                    throw dataFormat("旧版地形 tile 数据已截断", null);
                }
            }
            return output.toByteArray();
        } catch (final IOException exception) {
            throw dataFormat("旧版地形 tile 数据读取失败", exception);
        } finally {
            inflater.end();
        }
    }

    private static byte[] decodeBase64(final String encoded) throws DataFormatException {
        try {
            return Base64.getMimeDecoder().decode(encoded);
        } catch (final IllegalArgumentException exception) {
            throw dataFormat("地形 tile Base64 数据非法", exception);
        }
    }

    private static byte[] decodeLegacyBase64Payload(final String encoded) throws DataFormatException {
        try {
            return DatatypeConverter.parseBase64Binary(encoded);
        } catch (final IllegalArgumentException exception) {
            throw dataFormat("旧版地形 tile Base64 数据非法", exception);
        }
    }

    private static DataFormatException dataFormat(final String message,
                                                  final Throwable cause) {
        final DataFormatException exception = new DataFormatException(message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }
}