package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

final class JvmSettings {
    private static final String DIRECTIVES = "${compilerDirectives}";
    private static final String DIRECTIVE_OPTION = "-XX:CompilerDirectivesFile=";
    private static final String OLD_DEFAULT_DIRECTIVES_SHA256 = "a3a222125159094372acd3f5324e633f8573b09293ce14f805badb8e749695a7";
    private static final String DEFAULT_DIRECTIVES = """
            [
              { "match": ["com/fs/starfarer/combat/*.*"], "c1": {"Exclude": false}, "c2": {"Exclude": true} },
              { "match": ["com/fs/graphics/*.*", "com/fs/state/*.*", "com/fs/util/*.*"], "c1": {"Exclude": true}, "c2": {"Exclude": false, "Vectorize": true} }
            ]
            """;

    private JvmSettings() {}

    record Options(List<String> vm, List<String> game) {}

    static Path configure(GameInstall game, Path instance) throws IOException {
        Path file = instance.resolve("jvm.args");
        InstancePaths.confined(instance, file);
        if (Files.exists(file)) {
            if (!Files.isRegularFile(file)) throw new IOException("参数路径不是普通文件：" + file);
            return file;
        }
        Properties legacy = new Properties();
        Path old = instance.resolve("jvm.properties");
        InstancePaths.confined(instance, old);
        if (Files.exists(old)) {
            try (var reader = Files.newBufferedReader(old, StandardCharsets.UTF_8)) { legacy.load(reader); }
        }
        int min = number(legacy, "heap.minMiB", 512), max = number(legacy, "heap.maxMiB", 6144);
        int stack = number(legacy, "stack.KiB", 4096);
        if (min > max) throw new IOException(old + "：heap.minMiB 大于 heap.maxMiB");
        Map<String,String> properties = new LinkedHashMap<>();
        properties.put("ssoptimizer.font.ttf.enable", "true");
        properties.put("ssoptimizer.render.spritebatch.stats", "false");
        properties.put("ssoptimizer.renderthread.enable", "true");
        properties.put("ssoptimizer.render.spritebatch.enable", "false");
        properties.put("ssoptimizer.texcompress.enable", "false");
        properties.put("ssoptimizer.debug.buffermap", "false");
        properties.put("ssoptimizer.atlas.shipweapon", "true");
        for (String key : legacy.stringPropertyNames().stream().sorted().toList()) {
            if (List.of("heap.minMiB", "heap.maxMiB", "stack.KiB").contains(key)) continue;
            String value = legacy.getProperty(key);
            if (key.indexOf('=') >= 0 || (key + value).chars().anyMatch(c -> c == '\n' || c == '\r' || c == 0) || !vmOption("-D" + key + "=" + value)) {
                throw new IOException(old + "：无效或由宿主管理的 JVM 属性：" + key);
            }
            properties.put(key, value);
        }
        compilerDirectives(game, instance);
        StringBuilder text = new StringBuilder("""
                # UTF-8，每行一个游戏 JVM 参数。空行及以 # 开头的行忽略；路径含空格也无需引号。
                # 本文件创建后不再读取旧 jvm.properties。实例中的设置优先于内置默认值；启动器 JVM 显式指定的 SSO 系统属性优先于本文件。
                # 游戏目录、实例目录、类路径、独立宿主和 native 由启动器设置。移除本文件后将按旧配置或默认值重新生成。
                # 内存：-Xms 是初始堆，-Xmx 是堆上限，-Xss 是每个线程的栈。m 表示 MiB，k 表示 KiB。
                """);
        text.append("-Xms").append(min).append("m\n-Xmx").append(max).append("m\n-Xss").append(stack).append("k\n");
        text.append("""
                # HotSpot 编译和垃圾回收：默认使用 ZGC，预先提交初始堆。切换收集器时请替换 -XX:+UseZGC。
                -XX:+UnlockDiagnosticVMOptions
                -XX:+PrintCommandLineFlags
                -XX:+TieredCompilation
                -XX:+DisableExplicitGC
                -XX:+AlwaysPreTouch
                -XX:+UseZGC
                -XX:ReservedCodeCacheSize=256m
                # 优先使用游戏核心目录、游戏根目录中的 compiler_directives.txt；两处均缺失时使用实例的 compiler-directives.json。
                -XX:CompilerDirectivesFile=${compilerDirectives}
                -Djdk.xml.maxElementDepth=10000
                """);
        if (ArchiveProfile.FLAVOR.equals("dev")) text.append("""
                # 开发实例沿用现有游戏参数。使用 -Xverify:all 可启用全部字节码校验。
                -XX:-BytecodeVerificationLocal
                -XX:-BytecodeVerificationRemote
                """);
        text.append("""
                -Djava.util.Arrays.useLegacyMergeSort=true
                --enable-preview

                # SSOptimizer：font.ttf.enable 启用 TrueType 字体和缺失汉字回退；renderthread.enable 启用渲染线程分离。
                # texcompress.enable 建议保持 false，以保留原始纹理；atlas.shipweapon 控制舰船和武器图集。
                """);
        properties.forEach((k,v) -> text.append("-D").append(k).append('=').append(v).append('\n'));
        text.append("\n# 分隔线之后每行一个传给游戏的参数；启动命令 -- 后的参数会追加到这些参数之后。\n--\n");
        String content = text.toString();
        if (java.io.File.separatorChar == '\\') content = "\ufeff" + content.replace("\n", "\r\n");
        write(file, content, false);
        return file;
    }

    static Options read(GameInstall game, Path instance) throws IOException {
        Path file = configure(game, instance);
        var vm = new ArrayList<String>();
        var tail = new ArrayList<String>();
        int line = 0;
        boolean gameArgs = false;
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            line++;
            if (line == 1 && raw.startsWith("\ufeff")) raw = raw.substring(1);
            if (raw.isBlank() || raw.stripLeading().startsWith("#")) continue;
            if (raw.indexOf(0) >= 0) throw new IOException(file + ":" + line + " 含有 NUL 字符");
            if (gameArgs) { tail.add(raw); continue; }
            String arg = raw.stripLeading();
            if (arg.stripTrailing().equals("--")) { gameArgs = true; continue; }
            if (!arg.startsWith("-D")) arg = arg.stripTrailing();
            if (arg.equals(DIRECTIVE_OPTION + DIRECTIVES)) arg = DIRECTIVE_OPTION + compilerDirectives(game, instance);
            if (arg.startsWith(DIRECTIVE_OPTION + "${instance}/")) arg = DIRECTIVE_OPTION + instance.resolve(arg.substring((DIRECTIVE_OPTION + "${instance}/").length()));
            if (!vmOption(arg)) throw new IOException(file + ":" + line + " 不支持此 JVM 参数或此项由独立宿主管理：" + arg);
            vm.add(arg);
        }
        return new Options(List.copyOf(vm), List.copyOf(tail));
    }

    private static Path compilerDirectives(GameInstall game, Path instance) throws IOException {
        for (Path dir : List.of(game.core(), game.root())) {
            Path original = dir.resolve("compiler_directives.txt");
            if (Files.isRegularFile(original)) return original;
        }
        Path fallback = instance.resolve("compiler-directives.json");
        InstancePaths.confined(instance, fallback);
        if (Files.notExists(fallback)) {
            write(fallback, DEFAULT_DIRECTIVES, false);
        } else if (!Files.isRegularFile(fallback)) {
            throw new IOException("编译指令不是普通文件：" + fallback);
        } else if (OLD_DEFAULT_DIRECTIVES_SHA256.equals(GameInstall.digest(fallback))) {
            write(fallback, DEFAULT_DIRECTIVES, true);
        }
        return fallback;
    }

    private static void write(Path file, String text, boolean replace) throws IOException {
        Path tmp = Files.createTempFile(file.getParent(), "jvm-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            if (replace) Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(tmp); }
    }

    static boolean gameOption(String name) {
        return name.startsWith("ssoptimizer.") && !name.startsWith("ssoptimizer.standalone.") && !name.startsWith("ssoptimizer.native.path");
    }

    private static boolean vmOption(String arg) {
        if (arg.startsWith("-D")) {
            int end = arg.indexOf('=');
            String key = arg.substring(2, end < 0 ? arg.length() : end);
            if (key.isEmpty() || key.chars().anyMatch(Character::isWhitespace)) return false;
            return !key.startsWith("ssoptimizer.standalone.") && !key.startsWith("ssoptimizer.native.path")
                    && !key.startsWith("com.fs.starfarer.settings.paths.") && !key.startsWith("org.lwjgl.librarypath")
                    && !key.equals("com.fs.starfarer.settings.linux") && !key.equals("net.java.games.input.librarypath")
                    && !List.of("java.system.class.loader", "java.class.path", "java.library.path", "mixin.env.compatLevel").contains(key);
        }
        if (!arg.startsWith("-") || arg.equals("-") || arg.equals("--")) return false;
        if (arg.startsWith("-agentlib:jdwp=")) return true;
        // 路径和值必须与选项写在同一行，禁止 JVM 吞掉启动器添加的下一个参数。
        String option = arg.split("=", 2)[0];
        if (List.of("--add-opens", "--add-exports", "--add-modules", "--add-reads", "--enable-native-access", "--sun-misc-unsafe-memory-access", "--illegal-native-access").contains(option)) {
            return arg.length() > option.length() + 1;
        }
        return !List.of("-cp", "-classpath", "--class-path", "-jar", "-m", "--module", "-p", "--module-path", "--upgrade-module-path",
                        "--patch-module", "--limit-modules", "--enable-native-access", "--source", "-d", "--describe-module",
                        "-version", "--version", "-help", "--help", "-?", "-h", "-X", "--help-extra", "--list-modules", "--validate-modules", "--dry-run").contains(option)
                && !arg.startsWith("-Xbootclasspath") && !arg.startsWith("-Xrun") && !arg.startsWith("-javaagent:")
                && !arg.startsWith("-agentlib:") && !arg.startsWith("-agentpath:") && !arg.startsWith("-XX:Flags=") && !arg.startsWith("-XX:VMOptionsFile=");
    }

    private static int number(Properties p, String key, int def) throws IOException {
        String raw = p.getProperty(key);
        if (raw == null) return def;
        int n;
        try { n = Integer.parseInt(raw.strip()); }
        catch (NumberFormatException e) { throw new IOException(key + " 需要整数：" + raw, e); }
        if (n <= 0) throw new IOException(key + " 需要正整数：" + n);
        return n;
    }
}
