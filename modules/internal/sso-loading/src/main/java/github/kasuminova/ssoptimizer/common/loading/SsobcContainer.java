package github.kasuminova.ssoptimizer.common.loading;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析 native 编码器生成的小端 SSOBC 容器；任何结构校验失败均返回 null，由调用方记录缓存未命中。
 */
public final class SsobcContainer {
    static final int MAGIC             = 0x424F5353;
    static final int VERSION           = 1;
    static final int HEADER_BYTES      = 16;
    static final int LEVEL_ENTRY_BYTES = 16;
    static final int MAX_LEVELS        = 32;

    private final byte[]                            raw;
    private final TextureCompressionSupport.Format  format;
    private final int                               width;
    private final int                               height;
    private final List<Level>                       levels;
    private final int                               dataRegionOffset;

    private SsobcContainer(final byte[] raw,
                           final TextureCompressionSupport.Format format,
                           final int width,
                           final int height,
                           final List<Level> levels,
                           final int dataRegionOffset) {
        this.raw = raw;
        this.format = format;
        this.width = width;
        this.height = height;
        this.levels = levels;
        this.dataRegionOffset = dataRegionOffset;
    }

    record Level(int width, int height, int dataOffset, int dataLength) {
    }

    static SsobcContainer parse(final byte[] container) {
        if (container == null || container.length < HEADER_BYTES) {
            return null;
        }

        final ByteBuffer header = ByteBuffer.wrap(container).order(ByteOrder.LITTLE_ENDIAN);
        if (header.getInt(0) != MAGIC) {
            return null;
        }
        final int version = header.get(4) & 0xFF;
        final int formatId = header.get(5) & 0xFF;
        final int mipCount = header.getShort(6) & 0xFFFF;
        final int width = header.getInt(8);
        final int height = header.getInt(12);
        if (version != VERSION || mipCount < 1 || mipCount > MAX_LEVELS || width <= 0 || height <= 0) {
            return null;
        }
        final TextureCompressionSupport.Format format =
                TextureCompressionSupport.Format.fromNativeId(formatId);
        if (format == null) {
            return null;
        }

        final int tableBytes = mipCount * LEVEL_ENTRY_BYTES;
        if (container.length < HEADER_BYTES + tableBytes) {
            return null;
        }

        final List<Level> levels = new ArrayList<>(mipCount);
        int dataOffset = HEADER_BYTES + tableBytes;
        for (int i = 0; i < mipCount; i++) {
            final int entryOffset = HEADER_BYTES + i * LEVEL_ENTRY_BYTES;
            final int levelWidth = header.getInt(entryOffset);
            final int levelHeight = header.getInt(entryOffset + 4);
            final int dataLength = header.getInt(entryOffset + 8);
            if (levelWidth <= 0 || levelHeight <= 0
                    || dataLength != expectedLevelBytes(formatId, levelWidth, levelHeight)) {
                return null;
            }
            if ((long) dataOffset + dataLength > container.length) {
                return null;
            }
            levels.add(new Level(levelWidth, levelHeight, dataOffset, dataLength));
            dataOffset += dataLength;
        }
        if (dataOffset != container.length) {
            return null;
        }

        return new SsobcContainer(container, format, width, height,
                Collections.unmodifiableList(levels), HEADER_BYTES + tableBytes);
    }

    static int expectedLevelBytes(final int formatId, final int width, final int height) {
        final int blocksWide = (Math.max(1, width) + 3) / 4;
        final int blocksHigh = (Math.max(1, height) + 3) / 4;
        final int blockBytes = formatId == NativeTextureCompressor.FORMAT_BC1 ? 8 : 16;
        return blocksWide * blocksHigh * blockBytes;
    }

    static int fullChainLevels(final int width, final int height) {
        int levels = 1;
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        while (w > 1 || h > 1) {
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
            levels++;
        }
        return levels;
    }

    static int expectedContainerLength(final int formatId,
                                       final int width,
                                       final int height,
                                       final int mipCount) {
        long total = HEADER_BYTES + (long) mipCount * LEVEL_ENTRY_BYTES;
        int w = width;
        int h = height;
        for (int i = 0; i < mipCount; i++) {
            total += expectedLevelBytes(formatId, w, h);
            w = Math.max(1, w / 2);
            h = Math.max(1, h / 2);
        }
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    byte[] raw() {
        return raw;
    }

    TextureCompressionSupport.Format format() {
        return format;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    List<Level> levels() {
        return levels;
    }

    int dataRegionOffset() {
        return dataRegionOffset;
    }
}
