package github.kasuminova.ssoptimizer.common.loading;

import org.apache.log4j.Logger;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * 缓存文件原子落盘：临时文件 + fsync + rename。
 * <p>
 * 动机：纹理缓存此前直接 {@code Files.write}，实测抽样发现崩溃/中断留下的
 * 半文件残件（流式解压报错）。两级纹理缓存（ssotex / SSOBC）统一经此写入，
 * 调用方视角要么看到完整文件、要么看不到文件。
 */
final class AtomicFileWriter {
    private static final Logger LOGGER = Logger.getLogger(AtomicFileWriter.class);

    private AtomicFileWriter() {
    }

    /**
     * 把 {@code bytes} 原子写入 {@code target}：先写同目录临时文件并 fsync，
     * 再 rename 覆盖目标。失败抛 {@link IOException} 并尽力清理临时文件。
     */
    static void write(final Path target, final byte[] bytes) throws IOException {
        final Path directory = target.getParent();
        Files.createDirectories(directory);
        final Path temp = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // 文件系统不支持原子 rename（罕见）：退化为普通替换，语义仍正确（无非原子窗口外的残件）
                LOGGER.warn("[SSOptimizer] ATOMIC_MOVE 不受支持（" + target + "），退化为普通 rename");
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOGGER.warn("[SSOptimizer] 缓存临时文件清理失败（" + temp + "）", e);
                }
            }
        }
    }
}
