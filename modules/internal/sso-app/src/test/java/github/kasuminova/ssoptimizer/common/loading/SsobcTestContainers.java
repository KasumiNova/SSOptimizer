package github.kasuminova.ssoptimizer.common.loading;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 测试用 SSOBC 容器构造器：按契约布局（小端头 + 级别表 + 紧密块数据）
 * 生成合法容器字节，块数据填充确定性伪随机内容。
 */
final class SsobcTestContainers {
    private SsobcTestContainers() {
    }

    static byte[] build(final int formatId,
                        final int width,
                        final int height,
                        final int mipCount) {
        final int total = SsobcContainer.expectedContainerLength(formatId, width, height, mipCount);
        final ByteBuffer buffer = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(SsobcContainer.MAGIC);
        buffer.put((byte) SsobcContainer.VERSION);
        buffer.put((byte) formatId);
        buffer.putShort((short) mipCount);
        buffer.putInt(width);
        buffer.putInt(height);

        int levelWidth = width;
        int levelHeight = height;
        for (int level = 0; level < mipCount; level++) {
            buffer.putInt(levelWidth);
            buffer.putInt(levelHeight);
            buffer.putInt(SsobcContainer.expectedLevelBytes(formatId, levelWidth, levelHeight));
            buffer.putInt(0);
            levelWidth = Math.max(1, levelWidth / 2);
            levelHeight = Math.max(1, levelHeight / 2);
        }

        for (int i = SsobcContainer.HEADER_BYTES + mipCount * SsobcContainer.LEVEL_ENTRY_BYTES; i < total; i++) {
            buffer.put(i, (byte) (i * 31 + 7));
        }
        return buffer.array();
    }

    /** 便捷重载：完整 mip 链。 */
    static byte[] buildFullChain(final int formatId, final int width, final int height) {
        return build(formatId, width, height, SsobcContainer.fullChainLevels(width, height));
    }
}
