package github.kasuminova.ssoptimizer.common.loading;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SsobcContainerTest {
    @Test
    void parsesMultiLevelContainer() {
        final byte[] bytes = SsobcTestContainers.buildFullChain(NativeTextureCompressor.FORMAT_BC7, 64, 64);
        final SsobcContainer container = SsobcContainer.parse(bytes);

        assertNotNull(container);
        assertEquals(TextureCompressionSupport.Format.BC7, container.format());
        assertEquals(64, container.width());
        assertEquals(64, container.height());
        assertEquals(SsobcContainer.fullChainLevels(64, 64), container.levels().size());
        assertEquals(7, container.levels().size());

        final SsobcContainer.Level base = container.levels().get(0);
        assertEquals(64, base.width());
        assertEquals(64, base.height());
        assertEquals((64 / 4) * (64 / 4) * 16, base.dataLength());
        for (int i = 0; i < base.dataLength(); i++) {
            assertEquals(bytes[base.dataOffset() + i], container.raw()[base.dataOffset() + i]);
        }
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
        assertNull(SsobcContainer.parse(Arrays.copyOf(valid, valid.length - 1)));
        final byte[] badMagic = valid.clone();
        badMagic[0] = 'X';
        assertNull(SsobcContainer.parse(badMagic));
        final byte[] badVersion = valid.clone();
        badVersion[4] = 2;
        assertNull(SsobcContainer.parse(badVersion));
        final byte[] badFormat = valid.clone();
        badFormat[5] = 9;
        assertNull(SsobcContainer.parse(badFormat));
        final byte[] badLength = valid.clone();
        badLength[SsobcContainer.HEADER_BYTES + 8] += 1;
        assertNull(SsobcContainer.parse(badLength));
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
