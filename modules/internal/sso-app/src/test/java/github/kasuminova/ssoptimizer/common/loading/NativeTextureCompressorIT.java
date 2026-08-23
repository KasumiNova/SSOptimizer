package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lwjgl.BufferUtils;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * native BC 压缩器（libssoptimizer_texcompress）真实集成测试：
 * 不经 mock 直调 JNI，验证 isAvailable、BC1/BC3/BC7 压缩输出可被
 * {@link SsobcContainer} 解析且尺寸/mip 链与契约一致。
 * <p>
 * 需要已构建的 native 产物（默认相对路径
 * {@code ../sso-loading/native-texcompress/build/lib/main/release/libssoptimizer_texcompress.so}，
 * 可用 {@code -Dsso.texcompress.lib=<路径>} 覆盖）；产物缺失时整组跳过。
 */
class NativeTextureCompressorIT {
    private static final String LIB_PROPERTY = "sso.texcompress.lib";
    private static final String DEFAULT_LIB_PATH =
            "../sso-loading/native-texcompress/build/lib/main/release/libssoptimizer_texcompress.so";

    @BeforeAll
    static void setUpNativeLibrary() {
        final String configured = System.getProperty(LIB_PROPERTY);
        final Path lib = Path.of(configured != null && !configured.isBlank()
                ? configured
                : DEFAULT_LIB_PATH).toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isRegularFile(lib),
                "native texcompress 产物缺失，跳过集成测试: " + lib);
        System.setProperty("ssoptimizer.native.path.texcompress", lib.toString());
    }

    @Test
    void isAvailableLoadsNativeBackend() {
        assertTrue(NativeTextureCompressor.isAvailable(),
                "native texcompress 库应可加载且后端自报可用");
    }

    @Test
    void compressBc7SingleLevelProducesParseableContainer() {
        final byte[] container = NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC7,
                gradientRgba(256, 256, true), 256, 256, 1,
                NativeTextureCompressor.QUALITY_NORMAL, false);
        assertNotNull(container, "BC7 单级压缩不应返回 null");

        final SsobcContainer parsed = SsobcContainer.parse(container);
        assertNotNull(parsed, "native 输出应可被 SsobcContainer 解析");
        assertEquals(TextureCompressionSupport.Format.BC7, parsed.format());
        assertEquals(256, parsed.width());
        assertEquals(256, parsed.height());
        assertEquals(1, parsed.levels().size());
        final SsobcContainer.Level level = parsed.levels().get(0);
        assertEquals(256, level.width());
        assertEquals(256, level.height());
        assertEquals(SsobcContainer.expectedLevelBytes(
                NativeTextureCompressor.FORMAT_BC7, 256, 256), level.dataLength());
    }

    @Test
    void compressBc7FullMipChainHalvesLevelDimensions() {
        final int width = 256;
        final int height = 128;
        final int mipCount = SsobcContainer.fullChainLevels(width, height);
        final byte[] container = NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC7,
                gradientRgba(width, height, true), width, height, mipCount,
                NativeTextureCompressor.QUALITY_FAST, false);
        assertNotNull(container, "BC7 全 mip 链压缩不应返回 null");

        final SsobcContainer parsed = SsobcContainer.parse(container);
        assertNotNull(parsed);
        assertEquals(mipCount, parsed.levels().size(), "mip 层数应与 fullChainLevels 一致");

        int levelWidth = width;
        int levelHeight = height;
        for (final SsobcContainer.Level level : parsed.levels()) {
            assertEquals(levelWidth, level.width());
            assertEquals(levelHeight, level.height());
            assertEquals(SsobcContainer.expectedLevelBytes(
                    NativeTextureCompressor.FORMAT_BC7, levelWidth, levelHeight), level.dataLength());
            levelWidth = Math.max(1, levelWidth / 2);
            levelHeight = Math.max(1, levelHeight / 2);
        }
    }

    @Test
    void compressBc1AndBc3ProduceParseableContainers() {
        final ByteBuffer pixels = gradientRgba(128, 128, false);
        for (final int format : new int[]{
                NativeTextureCompressor.FORMAT_BC1, NativeTextureCompressor.FORMAT_BC3}) {
            final byte[] container = NativeTextureCompressor.compress(
                    format, pixels.duplicate(), 128, 128, 1,
                    NativeTextureCompressor.QUALITY_FAST, false);
            assertNotNull(container, "format=" + format + " 压缩不应返回 null");

            final SsobcContainer parsed = SsobcContainer.parse(container);
            assertNotNull(parsed, "format=" + format + " 输出应可解析");
            assertEquals(format, parsed.format().nativeId());
            assertEquals(128, parsed.width());
            assertEquals(128, parsed.height());
        }
    }

    @Test
    void compressBc1PunchThroughEncodesTransparentBlocksAsThreeColor() {
        // 8x8：右半 4x4 全透明（RGB 保留渐变，防止纯色退化掩盖编码缺陷）；
        // 块行优先排列，8x8 → 2x2 共 4 块，每块 8B
        final ByteBuffer pixels = BufferUtils.createByteBuffer(8 * 8 * 4);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                pixels.put((byte) (x * 30 + 20));
                pixels.put((byte) (y * 30 + 40));
                pixels.put((byte) ((x + y) * 15 + 60));
                pixels.put((byte) (x < 4 ? 255 : 0));
            }
        }
        pixels.flip();

        final byte[] container = NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC1, pixels.duplicate(), 8, 8, 1,
                NativeTextureCompressor.QUALITY_NORMAL, true);
        assertNotNull(container, "BC1 useAlpha 压缩不应返回 null");

        final SsobcContainer parsed = SsobcContainer.parse(container);
        assertNotNull(parsed);
        assertEquals(1, parsed.levels().size());
        final int dataOffset = parsed.levels().get(0).dataOffset();
        final byte[] raw = parsed.raw();
        for (int blockIndex = 0; blockIndex < 4; blockIndex++) {
            final int bxi = blockIndex % 2;
            final int base = dataOffset + blockIndex * 8;
            final int c0 = u16le(raw, base);
            final int c1 = u16le(raw, base + 2);
            final long selectors = u32le(raw, base + 4);
            if (bxi == 1) {
                // 全透明块：3-color 模式（c0 <= c1）且 selector 全 3（透明）
                assertTrue(c0 <= c1, "透明块必须编码为 3-color punch-through 模式");
                assertEquals(0xFFFFFFFFL, selectors, "全透明块 selector 应全 3");
            } else {
                // 不透明块：rgbcx 主路径（4-color），不得出现全透明形态
                assertFalse(c0 == 0 && c1 == 0 && selectors == 0xFFFFFFFFL,
                        "不透明块不得被误编码为全透明");
            }
        }

        // useAlpha=false：alpha 被忽略，右侧块按真实颜色编码，不得出现全透明形态
        final byte[] plain = NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC1, pixels.duplicate(), 8, 8, 1,
                NativeTextureCompressor.QUALITY_NORMAL, false);
        assertNotNull(plain);
        final SsobcContainer plainParsed = SsobcContainer.parse(plain);
        assertNotNull(plainParsed);
        final int plainBase = plainParsed.levels().get(0).dataOffset() + 8; // 块 (1,0)
        final byte[] plainRaw = plainParsed.raw();
        assertFalse(u16le(plainRaw, plainBase) == 0
                        && u16le(plainRaw, plainBase + 2) == 0
                        && u32le(plainRaw, plainBase + 4) == 0xFFFFFFFFL,
                "useAlpha=false 时 alpha 必须被忽略");
    }

    private static int u16le(final byte[] raw, final int offset) {
        return (raw[offset] & 0xFF) | ((raw[offset + 1] & 0xFF) << 8);
    }

    private static long u32le(final byte[] raw, final int offset) {
        return ((long) raw[offset] & 0xFF)
                | (((long) raw[offset + 1] & 0xFF) << 8)
                | (((long) raw[offset + 2] & 0xFF) << 16)
                | (((long) raw[offset + 3] & 0xFF) << 24);
    }

    @Test
    void compressRejectsInvalidPixelBuffer() {
        assertNull(NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC7,
                ByteBuffer.allocate(64), 8, 8, 1,
                NativeTextureCompressor.QUALITY_FAST, false),
                "非 direct 缓冲应被拒绝");
        assertNull(NativeTextureCompressor.compress(
                NativeTextureCompressor.FORMAT_BC7,
                BufferUtils.createByteBuffer(16), 64, 64, 1,
                NativeTextureCompressor.QUALITY_FAST, false),
                "长度不足的缓冲应被拒绝");
    }

    /** 合成确定性渐变纹理（含可辨识 RGBA 变化，避免纯色退化用例掩盖编码器缺陷）。 */
    private static ByteBuffer gradientRgba(final int width, final int height, final boolean withAlpha) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * 4);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffer.put((byte) (x * 255 / Math.max(1, width - 1)));
                buffer.put((byte) (y * 255 / Math.max(1, height - 1)));
                buffer.put((byte) ((x + y) & 0xFF));
                buffer.put((byte) (withAlpha ? 128 + ((x ^ y) & 0x7F) : 0xFF));
            }
        }
        buffer.flip();
        return buffer;
    }
}
