package ssoptimizer.standalone.launch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class JvmSettings {
    private JvmSettings() {}

    static List<String> read(Path instance) throws IOException {
        Path file = instance.resolve("jvm.properties");
        if (Files.notExists(file)) {
            Path tmp = Files.createTempFile(instance, "jvm-", ".properties");
            try {
                Files.writeString(tmp, "# MiB；使用大型模组组合时可提高 heap.maxMiB。\nheap.minMiB=512\nheap.maxMiB=6144\nstack.KiB=4096\n# 支持 ssoptimizer.* 游戏选项，例如 ssoptimizer.renderthread.enable=false。\n", StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(tmp); }
        }
        Properties props = new Properties();
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { props.load(input); }
        int min = number(props, "heap.minMiB", 512, 128, 1048576), max = number(props, "heap.maxMiB", 6144, 512, 1048576);
        int stack = number(props, "stack.KiB", 4096, 512, 65536);
        if (min > max) throw new IOException(file + "：heap.minMiB 大于 heap.maxMiB");
        var args = new ArrayList<>(List.of("-Xms" + min + "m", "-Xmx" + max + "m", "-Xss" + stack + "k"));
        for (String name : props.stringPropertyNames().stream().sorted().toList()) {
            if (List.of("heap.minMiB", "heap.maxMiB", "stack.KiB").contains(name)) continue;
            if (!gameOption(name)) throw new IOException(file + "：未知或保留的设置 " + name);
            args.add("-D" + name + "=" + props.getProperty(name));
        }
        return args;
    }

    static boolean gameOption(String name) {
        return name.startsWith("ssoptimizer.") && !name.startsWith("ssoptimizer.standalone.") && !name.startsWith("ssoptimizer.native.path");
    }

    private static int number(Properties p, String key, int def, int low, int high) throws IOException {
        String raw = p.getProperty(key);
        if (raw == null) return def;
        int n;
        try { n = Integer.parseInt(raw.strip()); }
        catch (NumberFormatException e) { throw new IOException(key + " 需要整数：" + raw, e); }
        if (n < low || n > high) throw new IOException(key + " 超出范围 [" + low + ", " + high + "]：" + n);
        return n;
    }
}
