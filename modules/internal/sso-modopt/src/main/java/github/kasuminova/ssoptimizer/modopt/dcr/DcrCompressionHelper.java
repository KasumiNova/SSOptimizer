package github.kasuminova.ssoptimizer.modopt.dcr;

import com.github.luben.zstd.ZstdInputStream;
import com.github.luben.zstd.ZstdOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** DCR 的 Zstd 压缩与解压实现，保留非 Zstd 魔数数据的 Deflate 读取兼容性。 */
public final class DcrCompressionHelper {

    private static final int IO_BUFFER_SIZE = 4096;

    private static final byte ZSTD_MAGIC_0 = (byte) 0x28;
    private static final byte ZSTD_MAGIC_1 = (byte) 0xB5;
    private static final byte ZSTD_MAGIC_2 = (byte) 0x2F;
    private static final byte ZSTD_MAGIC_3 = (byte) 0xFD;

    private DcrCompressionHelper() {
    }

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
