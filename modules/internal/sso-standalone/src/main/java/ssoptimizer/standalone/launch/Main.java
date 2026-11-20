package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public final class Main {
    private static final String USAGE = "用法：launch|prepare|doctor|config|import-save --game <已有游戏目录> --home <独立版本目录> --instance <实例目录> [-- <游戏参数>]\n导入原版存档：import-save ... --source <存档槽目录> --confirm-source <windows-en-rc8|windows-zh-rc8> [--slot <新槽名>]";
    private Main() {}

    public static void main(String[] args) {
        try { System.exit(run(args)); }
        catch (Exception e) {
            System.err.println("[Standalone] " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static int run(String[] argv) throws Exception {
        if (argv.length == 0 || List.of("--help", "-h", "help").contains(argv[0])) {
            System.out.println(USAGE); return argv.length == 0 ? 2 : 0;
        }
        if (Runtime.version().feature() != 25) throw new IOException("需要 Java 25 x64，当前为 " + Runtime.version());
        String command = argv[0];
        if (!Set.of("doctor", "prepare", "launch", "config", "import-save").contains(command)) throw new IllegalArgumentException(USAGE);
        DataLocation location = DataLocation.configured();
        if (location == DataLocation.GAME && command.equals("import-save")) {
            throw new IllegalArgumentException("SSO_USE_GAME_DATA=1 与 import-save 不能同时使用。请使用独立实例导入旧存档。");
        }
        var options = new HashMap<String,String>();
        var tail = new ArrayList<String>();
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--") && command.equals("launch")) {
                tail.addAll(List.of(argv).subList(i + 1, argv.length)); break;
            }
            boolean allowed = Set.of("--game", "--home", "--instance").contains(arg)
                    || command.equals("import-save") && Set.of("--source", "--confirm-source", "--slot").contains(arg);
            if (!allowed || ++i >= argv.length || argv[i].isBlank() || argv[i].startsWith("--") || options.putIfAbsent(arg, argv[i]) != null) {
                throw new IllegalArgumentException("无效或重复参数：" + arg + "\n" + USAGE);
            }
        }
        for (String required : List.of("--game", "--home", "--instance")) if (!options.containsKey(required)) throw new IllegalArgumentException("缺少 " + required + "\n" + USAGE);
        Path requested = Path.of(options.get("--game"));
        if (!Files.isDirectory(requested)) throw new IOException("找不到游戏目录：" + requested + "。请先安装 Starsector 0.98a-RC8 英文或常规汉化版：https://api.fossic.org/games");
        Path root = requested.toRealPath();
        Path home = Path.of(options.get("--home")).toRealPath();
        Distribution release = Distribution.load(home);
        if (command.equals("import-save") && ArchiveProfile.NAMESPACE.equals("obf")) {
            throw new IOException("obf 正式版不支持 import-save 转入 named 实例，请在开发环境中迁移到新槽。");
        }
        GameInstall game = GameInstall.inspect(root, location);
        Path instance = InstancePaths.validate(game, home, Path.of(options.get("--instance")), location);
        Path dataRoot = location.root(game, instance);
        if (command.equals("config")) {
            try (var lock = LaunchFiles.lock(instance)) {
                release.bindInstance(instance);
                System.out.println("游戏 JVM 参数（UTF-8）：" + JvmSettings.configure(game, instance));
            }
            return 0;
        }
        GameInstall.required(home.resolve("agent.jar"));
        GameInstall.required(home.resolve("payload.jar"));
        ReleaseLibraries.verify(home);
        if (command.equals("launch")) {
            ArchiveProfile.verify(game, home, release.abi());
            try (var guard = GameProcess.guard(instance)) {
                try (var lock = LaunchFiles.lock(instance)) { release.bindInstance(instance); }
                while (true) {
                    var result = GameProcess.launch(game, home, instance, dataRoot, tail);
                    if (!result.restartRequested()) return result.code();
                    System.out.println("[Standalone] 模组选择已保存，正在按新列表重新准备游戏。");
                }
            }
        }
        try (var lock = LaunchFiles.lock(instance)) {
            switch (command) {
                case "doctor" -> {
                    System.out.println("游戏数据目录：" + dataRoot + "，存档目录：" + instance.resolve("saves"));
                    String inputs = ArchiveProfile.verify(game, home, release.abi());
                    release.bindInstance(instance);
                    var libs = game.libraries(home);
                    var platform = PlatformFiles.inspect(game, home, instance.resolve("cache"));
                    System.out.printf("默认资源配置：%s%n游戏：%s%n核心：%s%nJava：%s (%s)%n运行平台：%s%n游戏 native：%s%nSSOptimizer native：%s%n实例：%s%n%s%n游戏库：%d；引导库：%d%n",
                            game.profile(), game.root(), game.core(), System.getProperty("java.home"), Runtime.version(), platform.id(),
                            platform.gameNative(), platform.optimizerNative(), instance, inputs, libs.size(), GameInstall.jars(home.resolve("lib")).size());
                }
                case "prepare" -> LaunchFiles.prepare(game, home, instance, location);
                case "import-save" -> {
                    if (!options.containsKey("--source") || !options.containsKey("--confirm-source")) throw new IOException("请用 --source 指定旧存档槽，并通过 --confirm-source " + game.profile() + " 等来源标识确认旧档的写入来源。");
                    Path source = Path.of(options.get("--source")).toRealPath();
                    ArchiveProfile.verify(game, home, release.abi());
                    release.bindInstance(instance);
                    Path imported = ArchiveProfile.importSave(game, home, instance, source, options.getOrDefault("--slot", source.getFileName().toString()), options.get("--confirm-source"));
                    System.out.println("新存档：" + imported);
                }
                default -> throw new AssertionError(command);
            }
        }
        return 0;
    }

}
