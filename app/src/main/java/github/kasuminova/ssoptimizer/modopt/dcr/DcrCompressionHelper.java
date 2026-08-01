package github.kasuminova.ssoptimizer.modopt.dcr;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * DCR 压缩内核替换辅助类：把 {@code data.scripts.combatanalytics.util.CompressionUtil} 的
 * deflate/inflate 内核替换为 Zstd，仅在 {@code byte[]} 层工作。
 * <p>
 * 动机：DCR 读档热点的压缩段为 {@code Deflater} 级别 9（最慢档）。本类把压缩内核换成更快的 Zstd，
 * 同时保留对旧存档的回退读。{@code CompressionProcessor} 会把 {@code CompressionUtil.compress(String):byte[]}
 * 与 {@code decompress(byte[]):String} 的方法体改写为委派至此——DCR 自身（非标准）的 Base64 层原封不动地
 * 包在本类字节之外、对本类透明，因此本类无需复刻其 Base64。
 * <p>
 * 格式识别：Zstd 帧自带 4 字节魔数 {@code 0x28 0xB5 0x2F 0xFD}（{@code 0xFD2FB528} 小端），
 * 据此区分新（Zstd）旧（zlib/Deflate）字节；旧存档读出后下一次保存即单向迁移为 Zstd。
 */
public final class DcrCompressionHelper {

    /** I/O 读缓冲大小。 */
    private static final int IO_BUFFER_SIZE = 4096;

    /** Zstd 帧魔数（落盘字节序）。 */
    private static final byte ZSTD_MAGIC_0 = (byte) 0x28;
    private static final byte ZSTD_MAGIC_1 = (byte) 0xB5;
    private static final byte ZSTD_MAGIC_2 = (byte) 0x2F;
    private static final byte ZSTD_MAGIC_3 = (byte) 0xFD;

    private DcrCompressionHelper() {
    }

    /**
     * 将文本以 UTF-8 编码后用 Zstd 压缩为单个 Zstd 帧。
     *
     * @param text 待压缩文本（DCR 战报 XML）
     * @return Zstd 帧字节（首 4 字节为 zstd 魔数）
     */
    public static byte[] compress(final String text) {
        final byte[] raw = text.getBytes(StandardCharsets.UTF_8);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(32, raw.length / 3));
             ZstdOutputStream zstd = new ZstdOutputStream(out)) {
            zstd.write(raw);
            zstd.close();
            return out.toByteArray();
        } catch (final IOException exception) {
            throw new IllegalStateException("DCR Zstd 压缩失败", exception);
        }
    }

    /**
     * 解压字节为文本。Zstd 魔数命中走 Zstd；否则按旧 DCR 的 zlib/Deflate 字节用 {@link Inflater} 回退读。
     *
     * @param bytes 压缩字节（已由 DCR 的 Base64 层解码而来）
     * @return 原始 UTF-8 文本
     */
    public static String decompress(final byte[] bytes) {
        if (looksLikeZstd(bytes)) {
            return decompressZstd(bytes);
        }
        return inflateLegacy(bytes);
    }

    private static boolean looksLikeZstd(final byte[] b) {
        return b.length >= 4
                && b[0] == ZSTD_MAGIC_0 && b[1] == ZSTD_MAGIC_1 && b[2] == ZSTD_MAGIC_2 && b[3] == ZSTD_MAGIC_3;
    }

    private static String decompressZstd(final byte[] bytes) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes);
             ZstdInputStream zstd = new ZstdInputStream(in);
             ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(IO_BUFFER_SIZE, bytes.length * 3))) {
            final byte[] buffer = new byte[IO_BUFFER_SIZE];
            int read;
            while ((read = zstd.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (final IOException exception) {
            throw new IllegalStateException("DCR Zstd 解压失败", exception);
        }
    }

    private static String inflateLegacy(final byte[] bytes) {
        final Inflater inflater = new Inflater();
        inflater.setInput(bytes);
        // ByteArrayOutputStream.close() 是 no-op，无需 try-with-resources（否则其声明的 IOException 需多余处理）。
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(IO_BUFFER_SIZE, bytes.length * 3));
        try {
            final byte[] buffer = new byte[IO_BUFFER_SIZE];
            while (!inflater.finished()) {
                final int read = inflater.inflate(buffer);
                if (read > 0) {
                    out.write(buffer, 0, read);
                    continue;
                }
                if (inflater.finished()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw new IllegalStateException("DCR 旧存档 deflate 数据要求字典");
                }
                if (inflater.needsInput()) {
                    throw new IllegalStateException("DCR 旧存档 deflate 数据被截断");
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        } catch (final DataFormatException exception) {
            throw new IllegalStateException("DCR 旧存档 deflate 数据损坏", exception);
        } finally {
            inflater.end();
        }
    }
}
