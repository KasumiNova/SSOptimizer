package github.kasuminova.ssoptimizer.reobf;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** 校验最终发行 JAR 中 SSO 自有类对指定原版游戏 ABI 的静态引用。 */
public final class VerifyObf {
    private VerifyObf() { }

    public static void main(String[] args) {
        try {
            var options = Options.parse(args);
            if (!verify(options)) System.exit(1);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("游戏静态链接校验失败: " + e.getMessage());
            System.exit(2);
        }
    }

    private record Options(List<Path> game, List<Path> inputs, List<Path> libraries) {
        static Options parse(String[] args) throws IOException {
            var game = new LinkedHashSet<Path>();
            var inputs = new LinkedHashSet<Path>();
            var libraries = new LinkedHashSet<Path>();
            for (int i = 0; i < args.length; i += 2) {
                var paths = switch (args[i]) {
                    case "--game" -> game;
                    case "--input" -> inputs;
                    case "--classpath" -> libraries;
                    default -> throw new IllegalArgumentException("未知参数 " + args[i]);
                };
                if (i + 1 == args.length || args[i + 1].startsWith("--")) {
                    throw new IllegalArgumentException("缺少 " + args[i] + " 的 JAR 路径");
                }
                paths.add(Path.of(args[i + 1]).toRealPath());
            }
            if (game.isEmpty() || inputs.isEmpty()) {
                throw new IllegalArgumentException("用法: VerifyObf --game <原版游戏 JAR> --input <最终发行 JAR> [--classpath <运行库 JAR>]，各选项可重复");
            }
            for (Path path : inputs) {
                if (game.contains(path) || libraries.contains(path)) throw new IOException("发行 JAR 也被传为依赖: " + path);
            }
            return new Options(List.copyOf(game), List.copyOf(inputs), List.copyOf(libraries));
        }
    }

    private static boolean verify(Options options) throws IOException {
        var originals = new LinkedHashMap<String, GameLinkage.Header>();
        for (Path jar : options.game) {
            scan(jar, reader -> {
                String name = reader.getClassName();
                if (!GameLinkage.gameNamespace(name)) return;
                var header = GameLinkage.header(reader, jar, true);
                var previous = originals.putIfAbsent(name, header);
                if (previous != null) throw new IOException("重复的游戏类 " + name + "，先前定义于 " + previous.origin);
            });
        }
        if (originals.isEmpty()) throw new IOException("原版游戏 JAR 无游戏类: " + options.game);
        var dependencies = new LinkedHashMap<String, GameLinkage.Header>();
        for (Path jar : options.libraries) {
            scan(jar, reader -> {
                String name = reader.getClassName();
                if (!GameLinkage.gameNamespace(name) && !dependencies.containsKey(name)) {
                    dependencies.put(name, GameLinkage.header(reader, jar, false));
                }
            });
        }
        boolean success = true;
        for (Path jar : options.inputs) {
            var classes = new LinkedHashMap<>(originals);
            var owned = new ArrayList<ClassNode>();
            scan(jar, reader -> {
                String name = reader.getClassName();
                if (GameLinkage.gameNamespace(name)) throw new IOException("发行 JAR 包含游戏类 " + name);
                if (owned(name)) {
                    var node = new ClassNode();
                    reader.accept(node, 0);
                    addInput(classes, new GameLinkage.Header(node, jar, false));
                    owned.add(node);
                } else {
                    addInput(classes, GameLinkage.header(reader, jar, false));
                }
            });
            if (owned.isEmpty()) throw new IOException("发行 JAR 无 SSO 自有类: " + jar);
            dependencies.forEach(classes::putIfAbsent);
            var index = new GameLinkage(classes);
            var errors = new LinkedHashSet<String>();
            for (ClassNode type : owned) new ObfReferences(index, type, errors).verify();
            for (String diagnostic : errors) System.err.println(jar + "!/" + diagnostic);
            System.out.printf("%s: %d 个 SSO 类，%d 个原版游戏类，%d 个静态链接错误%n",
                    jar, owned.size(), originals.size(), errors.size());
            success &= errors.isEmpty();
        }
        return success;
    }

    private static void addInput(Map<String, GameLinkage.Header> classes, GameLinkage.Header header) throws IOException {
        if (classes.putIfAbsent(header.name, header) != null) throw new IOException("重复发行类 " + header.name);
    }

    private static boolean owned(String name) {
        return name.startsWith("github/kasuminova/ssoptimizer/") || name.startsWith("ssoptimizer/standalone/");
    }

    @FunctionalInterface
    private interface ClassConsumer {
        void accept(ClassReader reader) throws IOException;
    }

    private static void scan(Path path, ClassConsumer visitor) throws IOException {
        try (var jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version()); var entries = jar.versionedStream()) {
            for (var it = entries.iterator(); it.hasNext();) {
                var entry = it.next();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.equals("module-info.class")) continue;
                try (var in = jar.getInputStream(entry)) {
                    var reader = new ClassReader(in);
                    if (!name.equals(reader.getClassName() + ".class")) {
                        throw new IOException("条目和类名不符: " + reader.getClassName());
                    }
                    visitor.accept(reader);
                } catch (IOException | RuntimeException e) {
                    throw new IOException(path + "!/" + name + ": " + e.getMessage(), e);
                }
            }
        }
    }
}
