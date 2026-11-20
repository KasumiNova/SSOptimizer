package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public final class Main {
    private static final String USAGE = "用法：launch|prepare|config --game <游戏目录> --home <发行版本目录> [-- <游戏参数>]";
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
            System.out.println(USAGE);
            return argv.length == 0 ? 2 : 0;
        }
        String command = argv[0];
        if (!Set.of("launch", "prepare", "config").contains(command)) throw new IllegalArgumentException(USAGE);
        var options = new HashMap<String, String>();
        var tail = new ArrayList<String>();
        for (int i = 1; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--") && command.equals("launch")) {
                tail.addAll(List.of(argv).subList(i + 1, argv.length));
                break;
            }
            if (!Set.of("--game", "--home").contains(arg) || ++i >= argv.length || argv[i].isBlank()
                    || argv[i].startsWith("--") || options.putIfAbsent(arg, argv[i]) != null) throw new IllegalArgumentException(USAGE);
        }
        if (!options.containsKey("--game") || !options.containsKey("--home")) throw new IllegalArgumentException(USAGE);
        Path home = Path.of(options.get("--home")).toRealPath();
        GameInstall game = GameInstall.inspect(Path.of(options.get("--game")).toRealPath());
        Path work = home.resolve("work");
        Files.createDirectories(work);
        try (var lock = GameProcess.guard(work)) {
            if (command.equals("config")) {
                System.out.println("游戏 JVM 参数：" + JvmSettings.configure(game, work));
                return 0;
            }
            boolean launch = command.equals("launch");
            do {
                Path plan = LaunchFiles.prepare(game, home, work, launch);
                if (!launch) return 0;
                int code = GameProcess.launch(game, home, work, plan, tail);
                if (code != 75) return code;
                System.out.println("[Standalone] 模组选择已保存，正在按新列表重新启动游戏。");
            } while (true);
        }
    }
}
