package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SsobcContainer} 解析校验：合法容器 roundtrip 与各类损坏形态。
 */
class SsobcContainerTest {
    @Test
    void parsesMultiLevelContainer() {
        final byte[] bytes = SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC7, 64, 64);
        final SsobcContainer container = SsobcContainer.parse(bytes);

        assertNotNull(container);
        assertEquals(TextureCompressionSupport.Format.BC7, container.format());
        assertEquals(64, container.width());
        assertEquals(64, container.height());
        // 64 -> 1 全链 7 级
        assertEquals(SsobcContainer.fullChainLevels(64, 64), container.levels().size());
        assertEquals(7, container.levels().size());

        final SsobcContainer.Level base = container.levels().get(0);
        assertEquals(64, base.width());
        assertEquals(64, base.height());
        assertEquals((64 / 4) * (64 / 4) * 16, base.dataLength());
        // 块数据与构造内容一致
        for (int i = 0; i < base.dataLength(); i++) {
            assertEquals(bytes[base.dataOffset() + i], container.raw()[base.dataOffset() + i]);
        }
        // 末级 1x1：单块 16B
        final SsobcContainer.Level last = container.levels().get(container.levels().size() - 1);
        assertEquals(1, last.width());
        assertEquals(16, last.dataLength());
        assertEquals(bytes.length, last.dataOffset() + last.dataLength());
    }

    @Test
    void bc1LevelSizesUseEightByteBlocks() {
        final SsobcContainer container = SsobcContainer.parse(
                SsobcTestContainers.build(NativeTextureCompressor.FORMAT_BC1, 64, 64, 1));
        assertNotNull(container);
        assertEquals(TextureCompressionSupport.Format.BC1, container.format());
        assertEquals((64 / 4) * (64 / 4) * 8, container.levels().get(0).dataLength());
    }

    @Test
    void rejectsMalformedContainers() {
        final byte[] valid = SsobcTestContainers.build(NativeTextureCompressor.FORMAT_BC7, 64, 64, 1);

        assertNull(SsobcContainer.parse(null));
        assertNull(SsobcContainer.parse(new byte[8]));
        // 截断
        assertNull(SsobcContainer.parse(Arrays.copyOf(valid, valid.length - 1)));
        // 魔数错误
        final byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertNull(SsobcContainer.parse(badMagic));
        // 版本错误
        final byte[] badVersion = valid.clone();
        badVersion[4] = 2;
        assertNull(SsobcContainer.parse(badVersion));
        // 未知格式
        final byte[] badFormat = valid.clone();
        badFormat[5] = 9;
        assertNull(SsobcContainer.parse(badFormat));
        // 级别表 dataLen 与格式/尺寸不符
        final byte[] badLength = valid.clone();
        badLength[SsobcContainer.HEADER_BYTES + 8] += 1;
        assertNull(SsobcContainer.parse(badLength));
        // 尾部多余字节（块数据未紧密拼接到末尾）
        assertNull(SsobcContainer.parse(Arrays.copyOf(valid, valid.length + 1)));
    }

    @Test
    void fullChainLevelsHalvesToOne() {
        assertEquals(1, SsobcContainer.fullChainLevels(1, 1));
        assertEquals(2, SsobcContainer.fullChainLevels(2, 2));
        assertEquals(7, SsobcContainer.fullChainLevels(64, 64));
        assertEquals(14, SsobcContainer.fullChainLevels(8192, 8192));
        assertEquals(9, SsobcContainer.fullChainLevels(300, 128));
    }
}
