package ssoptimizer.standalone.launch;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class GameProcess {
    private GameProcess() {}

    static FileChannel guard(Path instance) throws IOException {
        Files.createDirectories(instance);
        Path path = instance.resolve("launcher.lock");
        InstancePaths.confined(instance, path);
        FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        try {
            if (channel.tryLock() == null) throw new IOException("同一实例的游戏启动器已在运行：" + path);
            return channel;
        } catch (IOException | RuntimeException failure) {
            try { channel.close(); } catch (IOException close) { failure.addSuppressed(close); }
            if (failure instanceof OverlappingFileLockException) throw new IOException("同一进程已在启动此实例：" + path, failure);
            throw failure;
        }
    }

    static int launch(GameInstall game, Path home, Path instance, List<String> gameArguments) throws Exception {
        try (var log = GameProcessLog.open(instance)) {
            try { return launch(game, home, instance, gameArguments, log); }
            catch (Exception failure) { log.failure(failure); throw failure; }
        }
    }

    private static int launch(GameInstall game, Path home, Path instance, List<String> gameArguments, GameProcessLog log) throws Exception {
        Path agent = GameInstall.required(home.resolve("agent.jar"));
        PlatformFiles platform;
        JvmSettings.Options settings;
        try (var lock = LaunchFiles.lock(instance)) {
            settings = JvmSettings.read(game, instance);
            platform = PlatformFiles.inspect(game, home, instance.resolve("cache"));
        }
        var cp = new ArrayList<>(GameInstall.jars(home.resolve("lib")));
        cp.add(agent); cp.add(platform.lwjgl()); cp.add(platform.lwjglUtil());
        Path java = GameInstall.required(Path.of(System.getProperty("java.home"), "bin", platform.id().equals("windows") ? "java.exe" : "java"));
        var args = new ArrayList<>(List.of(java.toString(), "--enable-native-access=ALL-UNNAMED",
                "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Djava.system.class.loader=com.gtnewhorizons.retrofuturabootstrap.RfbSystemClassLoader",
                "-Dlog4j2.configurationFile=classpath:standalone-log4j2.xml", "-Dmixin.env.compatLevel=JAVA_17"));
        for (String pkg : List.of("java.base/java.lang", "java.base/java.lang.invoke", "java.base/java.lang.reflect", "java.base/java.lang.ref", "java.base/java.io",
                "java.base/java.nio", "java.base/java.text", "java.base/java.util", "java.base/java.util.concurrent",
                "java.base/java.util.concurrent.atomic", "java.base/java.util.concurrent.locks", "java.base/sun.nio.ch", "java.base/jdk.internal.ref",
                "java.desktop/java.awt", "java.desktop/java.awt.font")) args.add("--add-opens=" + pkg + "=ALL-UNNAMED");
        for (String pkg : List.of("java.base/jdk.internal.misc", "java.base/jdk.internal.ref", "java.base/sun.nio.ch")) args.add("--add-exports=" + pkg + "=ALL-UNNAMED");
        args.addAll(settings.vm());
        for (String key : System.getProperties().stringPropertyNames().stream().sorted().toList()) {
            if (JvmSettings.gameOption(key)) args.add("-D" + key + "=" + System.getProperty(key));
        }
        String debug = args.stream().filter(arg -> arg.equals("-Dssoptimizer.debug.enabled") || arg.startsWith("-Dssoptimizer.debug.enabled="))
                .reduce((previous, last) -> last).orElse("");
        if (debug.equalsIgnoreCase("-Dssoptimizer.debug.enabled=true")) args.add("-Djdk.attach.allowAttachSelf=true");
        for (String name : List.of("saves", "screenshots", "mods", "logs")) args.add("-Dcom.fs.starfarer.settings.paths." + name + "=" + instance.resolve(name));
        args.addAll(platform.arguments());
        args.add("-XX:ErrorFile=" + instance.resolve("logs").toString().replace("%", "%%") + File.separator + "hs_err_pid%p.log");
        args.add("-XX:-CreateCoredumpOnCrash");
        args.addAll(List.of("-Dssoptimizer.standalone.game=" + game.root(), "-Dssoptimizer.standalone.home=" + home,
                "-Dssoptimizer.standalone.instance=" + instance, "-javaagent:" + agent));
        for (Path path : cp) {
            if (path.toString().indexOf(File.pathSeparatorChar) >= 0) throw new IOException("引导类路径不能含路径分隔符 " + File.pathSeparator + "：" + path);
        }
        String classpath = cp.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        int cpIndex = args.size();
        args.addAll(List.of("-cp", classpath, "com.gtnewhorizons.retrofuturabootstrap.Main", "--gameDir", instance.toString(),
                "--tweakClass", "ssoptimizer.standalone.host.StandaloneTweaker", "--"));
        args.addAll(settings.game());
        args.addAll(gameArguments);
        ProcessBuilder builder = new ProcessBuilder(args).directory(game.core().toFile());
        boolean windows = platform.id().equals("windows");
        var env = builder.environment();
        if (platform.id().equals("linux")) env.putIfAbsent("mesa_glthread", "false");
        if (windows && windowsCommandLength(args) > 32767) {
            args.subList(cpIndex, cpIndex + 2).clear();
            if (classpath.length() >= 32767 || windowsCommandLength(args) > 32767) {
                throw new IOException("超过 Windows 的 32767 字符进程参数限制；请缩短游戏及实例路径，或减少启动参数。");
            }
            env.put(windowsVariable(env, "CLASSPATH"), classpath);
        }
        String libVar = windows ? windowsVariable(env, "PATH") : "LD_LIBRARY_PATH";
        String current = env.get(libVar);
        env.put(libVar, platform.optimizerNative() + File.pathSeparator + platform.gameNative() +
                (current == null || current.isBlank() ? "" : File.pathSeparator + current));
        log.message(java + " | " + game.profile() + " | " + platform.id() + " | " + instance);
        return log.run(builder);
    }

    private static String windowsVariable(java.util.Map<String, String> env, String name) throws IOException {
        var matches = env.keySet().stream().filter(name::equalsIgnoreCase).toList();
        if (matches.size() > 1) throw new IOException("Windows 环境变量重名：" + matches);
        return matches.isEmpty() ? name : matches.getFirst();
    }

    // CreateProcessW 的长度包含终止 NUL；为每一项预留引号、空格和反斜杠转义。
    private static long windowsCommandLength(List<String> args) {
        long length = 0;
        for (String arg : args) {
            length += arg.length() + 3L;
            int backslashes = 0;
            for (int i = 0; i < arg.length(); i++) {
                char c = arg.charAt(i);
                if (c == '\\') backslashes++;
                else {
                    if (c == '"') length += backslashes + 1L;
                    backslashes = 0;
                }
            }
            length += backslashes;
        }
        return length;
    }
}
