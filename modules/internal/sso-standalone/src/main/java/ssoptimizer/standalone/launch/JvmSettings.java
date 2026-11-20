package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

final class JvmSettings {
    private static final String DIRECTIVES = "${compilerDirectives}";
    private static final String DIRECTIVE_OPTION = "-XX:CompilerDirectivesFile=";
    private static final String DEFAULT_DIRECTIVES = """
            [
              { "match": ["com/fs/starfarer/combat/*.*"], "c1": {"Exclude": false}, "c2": {"Exclude": true} },
              { "match": ["com/fs/graphics/*.*", "com/fs/state/*.*", "com/fs/util/*.*"], "c1": {"Exclude": true}, "c2": {"Exclude": false, "Vectorize": true} }
            ]
            """;

    private JvmSettings() {}

    record Options(List<String> vm, List<String> game) {}

    static Path configure(GameInstall game, Path work) throws IOException {
        Path file = work.resolve("jvm.args");
        if (Files.exists(file)) return file;
        StringBuilder text = new StringBuilder("""
                # UTF-8，每行一个游戏 JVM 参数。空行及以 # 开头的行忽略；路径含空格也无需引号。
                # 启动器 JVM 显式指定的 SSO 系统属性优先于本文件。
                # 内存：-Xms 是初始堆，-Xmx 是堆上限，-Xss 是每个线程的栈。m 表示 MiB，k 表示 KiB。
                """);
        text.append("-Xms512m\n-Xmx6144m\n-Xss4096k\n");
        text.append("""
                # HotSpot 编译和垃圾回收：默认使用 ZGC，预先提交初始堆。切换收集器时请替换 -XX:+UseZGC。
                -XX:+UnlockDiagnosticVMOptions
                -XX:+PrintCommandLineFlags
                -XX:+TieredCompilation
                -XX:+DisableExplicitGC
                -XX:+AlwaysPreTouch
                -XX:+UseZGC
                -XX:ReservedCodeCacheSize=256m
                # 优先使用游戏核心目录、游戏根目录中的 compiler_directives.txt；两处均缺失时使用工作目录下的 compiler-directives.json。
                -XX:CompilerDirectivesFile=${compilerDirectives}
                -Djdk.xml.maxElementDepth=10000
                """);
        if (ArchiveProfile.FLAVOR.equals("dev")) text.append("""
                # 使用 -Xverify:all 可启用全部字节码校验。
                -XX:-BytecodeVerificationLocal
                -XX:-BytecodeVerificationRemote
                """);
        text.append("""
                -Djava.util.Arrays.useLegacyMergeSort=true
                --enable-preview

                # SSOptimizer：font.ttf.enable 启用 TrueType 字体和缺失汉字回退；renderthread.enable 启用渲染线程分离。
                # texcompress.enable 建议保持 false，以保留原始纹理；atlas.shipweapon 控制舰船和武器图集。
                """);
        text.append("""
                -Dssoptimizer.font.ttf.enable=true
                -Dssoptimizer.render.spritebatch.stats=false
                -Dssoptimizer.renderthread.enable=true
                -Dssoptimizer.render.spritebatch.enable=false
                -Dssoptimizer.texcompress.enable=false
                -Dssoptimizer.debug.buffermap=false
                -Dssoptimizer.atlas.shipweapon=true
                """);
        text.append("\n# 分隔线之后每行一个传给游戏的参数；启动命令 -- 后的参数会追加到这些参数之后。\n--\n");
        String content = text.toString();
        if (java.io.File.separatorChar == '\\') content = "\ufeff" + content.replace("\n", "\r\n");
        write(file, content);
        return file;
    }

    static Options read(GameInstall game, Path work) throws IOException {
        Path file = configure(game, work);
        var vm = new ArrayList<String>();
        var tail = new ArrayList<String>();
        int line = 0;
        boolean gameArgs = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line++;
            if (line == 1 && raw.startsWith("\ufeff")) raw = raw.substring(1);
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            if (gameArgs) { tail.add(raw); continue; }
            String arg = raw.stripLeading();
            if (arg.stripTrailing().equals("--")) { gameArgs = true; continue; }
            if (!arg.startsWith("-D")) arg = arg.stripTrailing();
            if (arg.equals(DIRECTIVE_OPTION + DIRECTIVES)) arg = DIRECTIVE_OPTION + compilerDirectives(game, work);
            vm.add(arg);
        }
        return new Options(List.copyOf(vm), List.copyOf(tail));
    }

    private static Path compilerDirectives(GameInstall game, Path work) throws IOException {
        for (Path dir : List.of(game.core(), game.root())) {
            Path original = dir.resolve("compiler_directives.txt");
            if (Files.isRegularFile(original)) return original;
        }
        Path fallback = work.resolve("compiler-directives.json");
        if (Files.notExists(fallback)) write(fallback, DEFAULT_DIRECTIVES);
        return fallback;
    }

    private static void write(Path file, String text) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), "jvm-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }

    static boolean gameOption(String name) {
        return name.startsWith("ssoptimizer.") && !name.startsWith("ssoptimizer.standalone.") && !name.startsWith("ssoptimizer.native.path");
    }

}
