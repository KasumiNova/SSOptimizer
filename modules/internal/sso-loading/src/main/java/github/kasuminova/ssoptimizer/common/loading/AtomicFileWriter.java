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

final class AtomicFileWriter {
    private static final Logger LOGGER = Logger.getLogger(AtomicFileWriter.class);

    private AtomicFileWriter() {
    }

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
