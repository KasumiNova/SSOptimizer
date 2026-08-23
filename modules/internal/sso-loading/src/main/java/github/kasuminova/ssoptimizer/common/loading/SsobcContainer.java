package github.kasuminova.ssoptimizer.common.loading;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * SSOBC 压缩纹理容器（{@link NativeTextureCompressor} 的输出，未做 zstd；
 * 落盘二压由 {@link CompressedTextureCache} 负责）。
 * <p>
 * 布局（多字节字段一律小端，与 native 写入端一致）：
 * <pre>
 * 头（16B）：magic "SSOB"(4) | version u8=1 | format u8 | mipCount u16 | width u32 | height u32
 * 级别表（每级 16B）：w u32 | h u32 | dataLen u32 | reserved u32
 * 随后按级别顺序紧密拼接各级压缩块数据（4×4 块，BC1 8B/块，BC3/BC7 16B/块）。
 * </pre>
 * 解析失败（截断/魔数或版本不符/级别表越界/块长与格式尺寸不符）一律返回 null，
 * 由调用方按「缓存损坏」处理（记日志 + miss）。
 */
public final class SsobcContainer {
    static final int MAGIC             = 0x424F5353; // "SSOB" 按小端 u32 读出的值
    static final int VERSION           = 1;
    static final int HEADER_BYTES      = 16;
    static final int LEVEL_ENTRY_BYTES = 16;
    /** mip 链层数上界（8192² 全链 14 层，32 已留足余量，用于防御性校验与解压长度上限）。 */
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

    /** 单级压缩块的位置与尺寸（{@code dataOffset} 相对容器起点）。 */
    record Level(int width, int height, int dataOffset, int dataLength) {
    }

    /**
     * 解析并校验 SSOBC 容器。
     *
     * @return 解析结果；任何损坏形态返回 null（不抛异常，不记日志——日志由调用方按上下文记）
     */
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
        // 契约要求各级块数据紧密拼接：末级结束必须恰好落在容器末尾
        if (dataOffset != container.length) {
            return null;
        }

        return new SsobcContainer(container, format, width, height,
                Collections.unmodifiableList(levels), HEADER_BYTES + tableBytes);
    }

    /** 单级压缩块字节数：4×4 块，BC1 8B/块，BC3/BC7 16B/块（尺寸不需 4 对齐，边缘块裁剪）。 */
    static int expectedLevelBytes(final int formatId, final int width, final int height) {
        final int blocksWide = (Math.max(1, width) + 3) / 4;
        final int blocksHigh = (Math.max(1, height) + 3) / 4;
        final int blockBytes = formatId == NativeTextureCompressor.FORMAT_BC1 ? 8 : 16;
        return blocksWide * blocksHigh * blockBytes;
    }

    /** 尺寸 {@code w×h} 的完整 mip 链层数（逐级减半直到 1×1）。 */
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

    /** 指定形态容器的精确总长度（缓存读侧的解压长度上限/完整性校验用）。 */
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

    /** 容器原始字节（含头与级别表）。 */
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

    /** 块数据区起点（头 + 级别表之后）；上传侧据此把数据区一次拷入 direct buffer 再逐级切窗。 */
    int dataRegionOffset() {
        return dataRegionOffset;
    }
}
