package ssoptimizer.standalone.bootstrap;

import ssoptimizer.standalone.launch.LaunchFiles;
import java.nio.file.Path;

public final class PrepareAgent {
    private PrepareAgent() {}

    public static void premain(String arguments) throws Exception {
        Path game = Path.of(System.getProperty("ssoptimizer.standalone.game"));
        Path home = Path.of(System.getProperty("ssoptimizer.standalone.home"));
        Path instance = LaunchFiles.validate(game, home, Path.of(System.getProperty("ssoptimizer.standalone.instance")));
        var session = LaunchFiles.lock(instance);
        try {
            LaunchFiles.checkModSelectionForLaunch(game);
            Path plan = LaunchFiles.prepare(game, home, instance);
            System.setProperty("ssoptimizer.standalone.plan", plan.toString());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { session.close(); }
                catch (Exception e) { System.err.println("释放实例锁失败：" + e); }
            }, "standalone-session"));
        } catch (Throwable failure) {
            try { session.close(); } catch (Exception e) { failure.addSuppressed(e); }
            throw failure;
        }
    }
}
