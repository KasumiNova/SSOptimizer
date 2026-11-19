package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

final class GameProcessLog implements AutoCloseable {
    private final Path path;
    private final OutputStream output;

    static GameProcessLog open(Path dataRoot) throws IOException {
        Path logs = dataRoot.resolve("logs");
        if (DataLocation.configured() == DataLocation.INSTANCE) InstancePaths.confined(dataRoot, logs);
        Files.createDirectories(logs);
        Path path = Files.createTempFile(logs, "launcher-" + System.currentTimeMillis() + "-", ".log");
        System.out.println("[Standalone] 本次启动日志：" + path);
        return new GameProcessLog(path, Files.newOutputStream(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
    }

    private GameProcessLog(Path path, OutputStream output) { this.path = path; this.output = output; }

    void message(String value) throws IOException {
        String line = "[Standalone " + Instant.now() + "] " + value;
        System.out.println(line);
        output.write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    void failure(Exception error) {
        var writer = new PrintWriter(output, true, StandardCharsets.UTF_8);
        error.printStackTrace(writer);
        if (writer.checkError()) System.err.println("无法记录启动异常：" + path);
    }

    int run(ProcessBuilder builder) throws IOException {
        Process child = builder.redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true).start();
        Thread shutdown = new Thread(() -> terminate(child), "standalone-exit");
        boolean registered = false;
        try {
            Runtime.getRuntime().addShutdownHook(shutdown);
            registered = true;
            var copy = new FutureTask<Void>(() -> {
                try (var in = new java.io.InputStreamReader(new java.io.FilterInputStream(child.getInputStream()) {
                    @Override public int read(byte[] data, int offset, int length) throws IOException {
                        int n = super.read(data, offset, length);
                        if (n > 0) output.write(data, offset, n);
                        return n;
                    }
                }, StandardCharsets.UTF_8)) {
                    char[] text = new char[8192];
                    for (int n; (n = in.read(text)) != -1;) {
                        System.out.print(new String(text, 0, n));
                        System.out.flush();
                    }
                } catch (IOException e) {
                    terminate(child);
                    throw e;
                }
                return null;
            });
            Thread.ofVirtual().name("standalone-game-output").start(copy);
            int code = child.waitFor();
            copy.get();
            message("游戏进程 " + child.pid() + " 退出，退出码 " + code + "，日志：" + path);
            return code;
        } catch (InterruptedException e) {
            terminate(child);
            Thread.currentThread().interrupt();
            throw new IOException("等待游戏进程时被中断，日志：" + path, e);
        } catch (ExecutionException e) {
            throw new IOException("保存游戏进程输出失败：" + path, e.getCause());
        } finally {
            if (child.isAlive()) terminate(child);
            if (registered) {
                try { Runtime.getRuntime().removeShutdownHook(shutdown); }
                catch (IllegalStateException stopping) {
                    System.getLogger(GameProcessLog.class.getName()).log(System.Logger.Level.DEBUG, "启动器正在退出", stopping);
                }
            }
        }
    }

    private static void terminate(Process process) {
        if (!process.isAlive()) return;
        process.destroy();
        try {
            if (!process.waitFor(8, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(8, TimeUnit.SECONDS)) System.err.println("游戏进程尚未退出，PID=" + process.pid());
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    @Override public void close() throws IOException { output.close(); }
}
