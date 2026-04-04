package github.kasuminova.ssoptimizer.common.save;

import org.junit.jupiter.api.Test;
import javax.xml.bind.DatatypeConverter;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TerrainTileCompressionHelper} 兼容性测试。
 * <p>
 * 约束新的 Zstd 写入格式必须能正确 round-trip，同时旧版 Deflater 压缩字符串也必须继续可读。
 */
class TerrainTileCompressionHelperTest {
    @Test
    void binaryTilesRoundTripWithZstdPrefix() throws DataFormatException {
        int[][] tiles = {
                {1, -1, 1, -1, 1},
                {-1, 1, -1, 1, -1},
                {1, 1, -1, -1, 1}
        };

        String encoded = TerrainTileCompressionHelper.encodeBinaryTiles(tiles);

        assertTrue(encoded.startsWith("SSOZ1:"));
        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeBinaryTiles(encoded, tiles.length, tiles[0].length));
    }

    @Test
    void legacyBinaryTilesRemainDecodable() throws DataFormatException {
        int[][] tiles = {
                {1, -1, 1, -1},
                {-1, -1, 1, 1},
                {1, 1, -1, -1}
        };

        String encoded = encodeLegacyBinaryTiles(tiles);

        assertFalse(encoded.startsWith("SSOZ1:"));
        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeBinaryTiles(encoded, tiles.length, tiles[0].length));
    }

    @Test
    void quaternaryTilesRoundTripWithZstdPrefix() throws DataFormatException {
        int[][] tiles = {
                {0, 1, 2, 3, 0},
                {3, 2, 1, 0, 1},
                {1, 3, 0, 2, 2}
        };

        String encoded = TerrainTileCompressionHelper.encodeQuaternaryTiles(tiles);

        assertTrue(encoded.startsWith("SSOZ1:"));
        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeQuaternaryTiles(encoded, tiles.length, tiles[0].length));
    }

    @Test
    void legacyQuaternaryTilesRemainDecodable() throws DataFormatException {
        int[][] tiles = {
                {0, 1, 2, 3},
                {3, 2, 1, 0},
                {1, 0, 3, 2}
        };

        String encoded = encodeLegacyQuaternaryTiles(tiles);

        assertFalse(encoded.startsWith("SSOZ1:"));
        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeQuaternaryTiles(encoded, tiles.length, tiles[0].length));
    }

    @Test
    void legacyQuaternaryTilesWithMultipleBase64ChunksRemainDecodable() throws DataFormatException {
        final int width = 512;
        final int height = 512;
        int[][] tiles = new int[width][height];
        int seed = 0x13572468;
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                seed = seed * 1664525 + 1013904223;
                tiles[x][y] = (seed >>> 30) & 0x3;
            }
        }

        String encoded = encodeLegacyQuaternaryTiles(tiles);

        assertTrue(encoded.length() > 136,
                "预期旧版压缩结果至少超过单个 100-byte chunk 的 Base64 长度");
        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeQuaternaryTiles(encoded, width, height));
    }

        @Test
        void legacyQuaternaryTilesMissingFinalPackedByteRemainDecodable() throws DataFormatException {
        int[][] tiles = {
            {0, 1, 2},
            {3, 2, 1},
            {1, 0, 3},
            {2, 3, 0}
        };

        byte[] packed = packLegacyQuaternaryTiles(tiles);
        String encoded = encodeLegacyPayload(Arrays.copyOf(packed, packed.length - 1));

        int[][] decoded = TerrainTileCompressionHelper.decodeQuaternaryTiles(encoded, tiles.length, tiles[0].length);
        int[][] expected = {
            {0, 1, 0},
            {3, 2, 0},
            {1, 0, 0},
            {2, 3, 0}
        };

        assertMatrixEquals(expected, decoded);
        }

    @Test
    void quaternaryTilesAcceptWrappedBase64Whitespace() throws DataFormatException {
        int[][] tiles = {
                {0, 1, 2, 3},
                {3, 2, 1, 0},
                {1, 3, 0, 2}
        };

        String encoded = encodeLegacyQuaternaryTiles(tiles);
        int firstBreak = Math.max(1, encoded.length() / 3);
        int secondBreak = Math.max(firstBreak + 1, (encoded.length() * 2) / 3);
        String wrapped = encoded.substring(0, firstBreak)
            + "\n  "
            + encoded.substring(firstBreak, secondBreak)
            + "\n"
            + encoded.substring(secondBreak);

        assertMatrixEquals(tiles, TerrainTileCompressionHelper.decodeQuaternaryTiles(wrapped, tiles.length, tiles[0].length));
    }

    private static String encodeLegacyBinaryTiles(final int[][] tiles) {
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

        return encodeLegacyPayload(packed);
    }

    private static String encodeLegacyQuaternaryTiles(final int[][] tiles) {
        return encodeLegacyPayload(packLegacyQuaternaryTiles(tiles));
    }

    private static byte[] packLegacyQuaternaryTiles(final int[][] tiles) {
        final int width = tiles.length;
        final int height = tiles[0].length;
        final int tileCount = width * height;
        final byte[] packed = new byte[(tileCount + 3) >>> 2];

        for (int index = 0; index < tileCount; index++) {
            final int x = index % width;
            final int y = index / width;
            final int byteIndex = index >>> 2;
            final int shift = 6 - ((index & 3) << 1);
            packed[byteIndex] = (byte) (packed[byteIndex] | ((tiles[x][y] & 0x3) << shift));
        }

        return packed;
    }

    private static String encodeLegacyPayload(final byte[] packed) {
        final Deflater deflater = new Deflater();
        deflater.setInput(packed);
        deflater.finish();

        final byte[] buffer = new byte[100];
        final StringBuilder output = new StringBuilder();
        while (!deflater.finished()) {
            final int written = deflater.deflate(buffer);
            output.append(DatatypeConverter.printBase64Binary(java.util.Arrays.copyOf(buffer, written)));
        }
        deflater.end();
        return output.toString();
    }

    private static void assertMatrixEquals(final int[][] expected,
                                           final int[][] actual) {
        assertArrayEquals(expected, actual);
        for (int index = 0; index < expected.length; index++) {
            assertArrayEquals(expected[index], actual[index], "tile 列 " + index + " 不匹配");
        }
    }
}