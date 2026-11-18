package ssoptimizer.standalone.instance;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class InstanceLock {
    @FunctionalInterface
    interface Action<T> { T run(Path state) throws IOException; }

    static <T> T with(Path instance, Action<T> action) throws IOException {
        Path state = InstanceFiles.directory(instance, ".standalone-layout");
        Path file = InstanceFiles.child(state, "instance.lock");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                InstanceFiles.NO_LINK); FileLock lock = channel.tryLock()) {
            if (lock == null) throw new IOException("其他进程正在准备实例：" + instance);
            return action.run(state);
        } catch (OverlappingFileLockException e) {
            throw new IOException("另一个任务正在准备实例：" + instance, e);
        }
    }

    private InstanceLock() {}
}
