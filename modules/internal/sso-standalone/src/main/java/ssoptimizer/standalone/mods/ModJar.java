package ssoptimizer.standalone.mods;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

final class ModJar {
    private static final System.Logger LOG = System.getLogger(ModJar.class.getName());
    private static final Set<String> LOADER_BASES = Set.of("java/lang/ClassLoader", "java/net/URLClassLoader",
            "java/security/SecureClassLoader");
    private static final String PLUGIN = "github/kasuminova/ssoptimizer/SSOptimizerModPlugin.class";

    private ModJar() {}

    static List<Path> externalCoremods(Path dir) throws IOException {
        if (Files.notExists(dir)) return List.of();
        var result = new ArrayList<Path>();
        try (var entries = Files.list(dir)) {
            for (Path path : entries.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                try (JarFile jar = open(path)) {
                    if (jar.getJarEntry("coremod.toml") != null) result.add(path);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void rejectCoremod(JarFile jar, Path path) throws IOException {
        if (jar.getJarEntry("coremod.toml") != null) {
            throw new IOException(path + " 包含 coremod.toml，需要 NanoForge，请从独立实例移除该 coremod 或改用 NanoForge 启动");
        }
    }

    static boolean inspect(Path path, String id, String source, boolean own, String plugin) throws IOException {
        try (JarFile jar = open(path)) {
            rejectCoremod(jar, path);
            if (!own && jar.getJarEntry(PLUGIN) != null) throw new IOException("检测到第二份 SSOptimizer 游戏入口：" + path);
            var manifest = jar.getManifest();
            String attribute = manifest == null ? null : manifest.getMainAttributes().getValue("SSOptimizer-Namespace");
            if (attribute != null) {
                String fromJar = namespace(attribute, path.toString());
                if (source != null && !source.equals(fromJar)) {
                    throw new IOException(path + " 的 SSOptimizer-Namespace=" + fromJar + " 与 mod_info.json 的 ssoptimizerNamespace=" + source + " 冲突");
                }
                source = fromJar;
            }
            if (own) {
                if ("obf".equals(source)) throw new IOException(path + " 将已挂载的 named payload 声明为 obf");
                return true;
            }
            inspectClasses(jar, id, plugin);
            return "named".equals(source);
        } catch (IOException e) {
            throw new IOException("模组 " + id + " [" + path + "]：" + e.getMessage(), e);
        }
    }

    static String namespace(String value, String where) throws IOException {
        if (value.equals("obf") || value.equals("named")) return value;
        throw new IOException(where + "：未知来源命名空间 " + value + "，应声明 obf 或 named");
    }

    private static JarFile open(Path path) throws IOException {
        try {
            return new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
        } catch (IOException e) {
            throw new IOException("无法读取模组归档：" + path, e);
        }
    }

    private static void inspectClasses(JarFile jar, String id, String plugin) throws IOException {
        String entrypoint = plugin == null ? null : plugin.replace('.', '/') + ".class";
        try (var entries = jar.versionedStream()) {
            var iter = entries.filter(e -> !e.getName().startsWith("META-INF/") && e.getName().endsWith(".class")).iterator();
            while (iter.hasNext()) {
                JarEntry entry = iter.next();
                try (var in = jar.getInputStream(entry)) {
                    ClassModel model = ClassFile.of().parse(in.readAllBytes());
                    String parent = model.superclass().map(c -> c.asInternalName()).orElse("");
                    if (entry.getName().equals(entrypoint) && (isNanoForge(parent)
                            || model.interfaces().stream().anyMatch(c -> isNanoForge(c.asInternalName())))) {
                        throw new IOException("游戏入口 " + entrypoint + " 的父类型需要 NanoForge");
                    }
                    if (LOADER_BASES.contains(parent)) {
                        LOG.log(System.Logger.Level.WARNING, "模组 {0} 的 {1}!/{2} 定义了加载器 {3} 的子类；请核对其加载的游戏脚本是否使用 named 归档",
                                id, jar.getName(), entry.getName(), parent);
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("无法解析模组类：" + jar.getName() + "!/" + entry.getName(), e);
                }
            }
        }
    }

    private static boolean isNanoForge(String name) {
        return name.startsWith("io/github/nanoforged/");
    }
}
