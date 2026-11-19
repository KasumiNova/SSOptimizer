package github.kasuminova.ssoptimizer.standalone.remap.reflect;

import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.format.MappingFormat;
import net.fabricmc.mappingio.tree.MappingTreeView;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

final class ReflectionIndex {
    private static final System.Logger LOG = System.getLogger(ReflectionNames.class.getName());
    private final ClassLoader loader;
    private final Map<String, Shape> game = new HashMap<>();
    private final Set<String> mounted = new HashSet<>();
    private final Map<String, String> classes = new HashMap<>();
    private final Map<String, String> legacy = new HashMap<>();
    private final Set<String> ambiguous = new HashSet<>();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    private record Alias(String target, String desc) { }
    private record Decl(String desc, int access) { }
    enum Scope { LOOKUP, PUBLIC, DECLARED }

    private static final class Names {
        final Map<String,List<Decl>> declarations = new HashMap<>();
        final Map<String,List<Alias>> aliases = new HashMap<>();

        void add(String name, String desc, int flags) {
            declarations.computeIfAbsent(name, k -> new ArrayList<>()).add(new Decl(desc, flags));
        }

        boolean declared(String name, String desc, boolean partial, boolean inherited, boolean itfMethod) {
            return declared(name, desc, partial, inherited, itfMethod, false);
        }

        boolean declared(String name, String desc, boolean partial, boolean inherited, boolean itfMethod, boolean publicOnly) {
            for (Decl entry : declarations.getOrDefault(name, List.of())) {
                if (publicOnly && (entry.access & Opcodes.ACC_PUBLIC) == 0) continue;
                if (inherited && ((entry.access & Opcodes.ACC_PRIVATE) != 0 || itfMethod && (entry.access & Opcodes.ACC_STATIC) != 0)) continue;
                if (partial ? entry.desc.startsWith(desc) : entry.desc.equals(desc)) return true;
            }
            return false;
        }
    }

    private static final class Shape {
        final Path archive;
        final Names methods = new Names(), fields = new Names();
        Shape(Path archive) { this.archive = archive; }
        Names names(boolean method) { return method ? methods : fields; }
    }

    private final ClassValue<Boolean> owned = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            Shape shape = game.get(type.getName());
            if (shape == null || type.getClassLoader() != loader) return false;
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return false;
            try {
                var url = source.getLocation();
                if (url.getProtocol().equals("jar")) {
                    var connection = (JarURLConnection) url.openConnection();
                    String entry = connection.getEntryName();
                    String logical = type.getName().replace('.', '/') + ".class";
                    if (entry != null && !entry.isEmpty() && !logical.equals(entry)
                            && !(entry.startsWith("META-INF/versions/") && entry.endsWith("/" + logical))) return false;
                    url = connection.getJarFileURL();
                }
                return url.getProtocol().equals("file") && shape.archive.equals(Path.of(url.toURI()).toRealPath());
            } catch (IOException | URISyntaxException | IllegalArgumentException ex) {
                warn(type.getName(), "无法辨认定义来源：" + ex);
                return false;
            }
        }
    };

    private final ClassValue<List<Class<?>>> hierarchies = new ClassValue<>() {
        @Override protected List<Class<?>> computeValue(Class<?> type) {
            var hierarchy = new LinkedHashSet<Class<?>>();
            collect(type, hierarchy);
            return List.copyOf(hierarchy);
        }
    };

    private void collect(Class<?> type, Set<Class<?>> out) {
        if (type == null || !out.add(type)) return;
        collect(type.getSuperclass(), out);
        for (Class<?> iface : type.getInterfaces()) collect(iface, out);
    }

    private ReflectionIndex(ClassLoader loader) { this.loader = loader; }

    static ReflectionIndex read(Path tiny, List<Path> jars, ClassLoader loader) throws IOException {
        var index = new ReflectionIndex(loader);
        var tree = new MemoryMappingTree();
        try (var raw = new BufferedInputStream(Files.newInputStream(tiny))) {
            raw.mark(2);
            boolean gzip = raw.read() == 0x1f && raw.read() == 0x8b;
            raw.reset();
            try (var text = new InputStreamReader(gzip ? new GZIPInputStream(raw) : raw, StandardCharsets.UTF_8)) {
                MappingReader.read(text, MappingFormat.TINY_2_FILE, tree);
            }
        }
        if (!"obf".equals(tree.getSrcNamespace()) || !tree.getDstNamespaces().contains("named"))
            throw new IOException("反射名称需要 Tiny obf -> named 映射：" + tiny);
        int ns = tree.getNamespaceId("named");
        Set<String> relevant = new HashSet<>();
        for (var cls : tree.getClasses()) {
            String dest = cls.getName(ns);
            relevant.add((dest == null ? cls.getSrcName() : dest).replace('/', '.'));
        }
        for (Path jar : jars) index.scan(jar.toRealPath(), relevant);
        for (var cls : tree.getClasses()) {
            String original = cls.getSrcName().replace('/', '.');
            String dest = cls.getName(ns);
            String name = dest == null ? original : dest.replace('/', '.');
            Shape gameType = index.game.get(name);
            if (gameType == null) continue;
            if (!original.equals(name)) {
                index.classes.put(original, name);
                var previous = index.legacy.putIfAbsent(name, original);
                if (previous != null && !previous.equals(original)) index.ambiguous.add(name);
            }
            for (var field : cls.getFields()) alias(gameType.fields, field, ns, false);
            for (var method : cls.getMethods()) alias(gameType.methods, method, ns, true);
        }
        return index;
    }

    private static void alias(Names names, MappingTreeView.MemberMappingView m, int ns, boolean method) {
        String old = m.getSrcName(), target = m.getName(ns);
        String fixed = validName(target == null ? old : target, method);
        if (!old.equals(fixed)) names.aliases.computeIfAbsent(old, k -> new ArrayList<>()).add(new Alias(fixed, m.getDesc(ns)));
    }

    private void scan(Path archive, Set<String> relevant) throws IOException {
        try (var jar = new JarFile(archive.toFile(), false, ZipFile.OPEN_READ, Runtime.version()); var entries = jar.versionedStream()) {
            for (var iter = entries.iterator(); iter.hasNext();) {
                var entry = iter.next();
                String path = entry.getName();
                if (!path.endsWith(".class") || path.startsWith("META-INF/") || path.equals("module-info.class")) continue;
                String name = path.substring(0, path.length() - 6).replace('/', '.');
                if (!mounted.add(name) || !relevant.contains(name)) continue;
                var shape = new Shape(archive);
                try (var in = jar.getInputStream(entry)) {
                    declarations(in, name, shape);
                } catch (RuntimeException | java.util.zip.ZipException | java.io.EOFException ex) {
                    indexWarning(archive, path, ex);
                    continue;
                }
                game.put(name, shape);
            }
        }
    }

    private static void indexWarning(Path archive, String entry, Exception ex) {
        LOG.log(System.Logger.Level.WARNING, "跳过反射名称索引条目 " + archive + "!/" + entry + "：" + ex);
    }

    private static void declarations(InputStream stream, String name, Shape shape) throws IOException {
        byte[] bytes = stream.readAllBytes();
        if (bytes.length < 10 || java.nio.ByteBuffer.wrap(bytes).getInt() != 0xcafebabe) throw new IllegalArgumentException("无效的类文件头");
        var reader = new ClassReader(bytes);
        if (!reader.getClassName().replace('/', '.').equals(name)) throw new IllegalArgumentException("类名与条目不一致：" + reader.getClassName());
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public FieldVisitor visitField(int flags, String n, String d, String sig, Object val) {
                shape.fields.add(n, d, flags); return null;
            }
            @Override public MethodVisitor visitMethod(int flags, String n, String d, String sig, String[] ex) {
                shape.methods.add(n, d, flags); return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    boolean owns(Class<?> type) { return type != null && type.getClassLoader() == loader && owned.get(type); }

    /**
     * 返回该类在混淆命名空间中的名称，供假定混淆名的模组比较使用。
     *
     * <p>仅对自身加载的游戏类生效；数组按描述符逐层还原元素名，未映射类与
     * 多个混淆名折叠到同一目标名的情形返回当前名称。
     */
    String legacyName(Class<?> type) {
        Class<?> element = type;
        while (element.isArray()) element = element.getComponentType();
        String name = type.getName();
        if (!owns(element) || element.isPrimitive()) return name;
        String retired = legacy.get(element.getName());
        if (retired == null || ambiguous.contains(element.getName())) return name;
        return type.isArray() ? name.substring(0, name.indexOf('L') + 1) + retired + ";" : retired;
    }

    String className(String name) {
        if (name.startsWith("[")) {
            int i = 0;
            while (i < name.length() && name.charAt(i) == '[') i++;
            if (i >= name.length() || name.charAt(i) != 'L' || !name.endsWith(";")) return name;
            String inner = name.substring(i + 1, name.length() - 1);
            String mapped = binaryName(inner);
            return inner.equals(mapped) ? name : name.substring(0, i + 1) + mapped + ";";
        }
        return binaryName(name);
    }

    private String binaryName(String name) {
        String mapped = classes.get(name);
        if (mapped == null) return name;
        if (mounted.contains(name)) { warn(name, "当前游戏还定义了同名类，保留原名"); return name; }
        return mapped;
    }

    String member(Class<?> owner, String raw, boolean method, String desc, boolean partial) {
        return member(owner, raw, method, desc, partial, Scope.LOOKUP);
    }

    String member(Class<?> owner, String raw, boolean method, String desc, boolean partial, Scope scope) {
        if (raw == null || owner == null || owner.isPrimitive() || owner.isArray()) return raw;
        boolean own = owns(owner);
        if (scope == Scope.DECLARED && !own) return raw;
        if (!own && (owner.getClassLoader() == null || mounted.contains(owner.getName()))) return raw;
        var types = hierarchies.get(owner);
        var candidates = new LinkedHashSet<String>();
        boolean literal = false;
        for (var type : types) {
            if (!owns(type)) continue;
            Names names = game.get(type.getName()).names(method);
            if (scope != Scope.DECLARED || type == owner)
                literal |= names.declared(raw, desc, partial, type != owner, method && type.isInterface(), scope == Scope.PUBLIC);
            for (var alias : names.aliases.getOrDefault(raw, List.of())) {
                if (!(partial ? alias.desc.startsWith(desc) : alias.desc.equals(desc))) continue;
                if (scope == Scope.DECLARED && type != owner && (!method || !overrides(owner, type, alias))) continue;
                if (names.declared(alias.target, alias.desc, false, type != owner, method && type.isInterface(), !own || scope == Scope.PUBLIC)) candidates.add(alias.target);
            }
        }
        String sanitized = validName(raw, method);
        if (own && !sanitized.equals(raw) && declared(scope == Scope.DECLARED ? List.of(owner) : types, owner, method, sanitized, desc, partial, scope == Scope.PUBLIC)) candidates.add(sanitized);
        if (candidates.isEmpty()) return raw;
        if (literal || candidates.size() > 1) {
            warn(owner.getName() + "." + raw + ":" + desc, "多个有效名称，保留原名：" + candidates);
            return raw;
        }
        String target = candidates.iterator().next();
        for (var type : types) {
            if (scope == Scope.DECLARED) break;
            if (owns(type)) continue;
            var external = otherDeclarations.get(type);
            if (external == UNKNOWN) return raw;
            var names = external.names(method);
            if (names.declared(raw, desc, partial, false, false) || !own && names.declared(target, desc, partial, false, false)) return raw;
        }
        return target;
    }

    private boolean overrides(Class<?> owner, Class<?> parent, Alias alias) {
        Names base = game.get(parent.getName()).methods, child = game.get(owner.getName()).methods;
        for (Decl inherited : base.declarations.getOrDefault(alias.target, List.of())) {
            if (!inherited.desc.equals(alias.desc) || (inherited.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) != 0) continue;
            if ((inherited.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 && !owner.getPackageName().equals(parent.getPackageName())) continue;
            for (Decl direct : child.declarations.getOrDefault(alias.target, List.of())) {
                if (direct.desc.equals(alias.desc) && (direct.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) return true;
            }
        }
        return false;
    }

    private boolean declared(List<Class<?>> types, Class<?> owner, boolean method, String name, String desc, boolean partial, boolean publicOnly) {
        for (var type : types) if (owns(type) && game.get(type.getName()).names(method).declared(name, desc, partial, type != owner, method && type.isInterface(), publicOnly)) return true;
        return false;
    }

    private static final Shape UNKNOWN = new Shape(null);
    private final ClassValue<Shape> otherDeclarations = new ClassValue<>() {
        @Override protected Shape computeValue(Class<?> type) {
            String name = type.getName(), entry = name.replace('.', '/') + ".class";
            Shape shape = new Shape(null);
            try {
                if (type.getModule().isNamed()) {
                    try (InputStream in = type.getModule().getResourceAsStream(entry)) {
                        if (in == null) return UNKNOWN;
                        declarations(in, name, shape);
                    }
                } else {
                    var source = type.getProtectionDomain().getCodeSource();
                    if (source == null || source.getLocation() == null) return UNKNOWN;
                    var url = source.getLocation();
                    if (url.getProtocol().equals("jar")) url = ((JarURLConnection) url.openConnection()).getJarFileURL();
                    if (!url.getProtocol().equals("file")) return UNKNOWN;
                    Path path = Path.of(url.toURI());
                    if (Files.isDirectory(path)) {
                        try (InputStream in = Files.newInputStream(path.resolve(entry))) { declarations(in, name, shape); }
                    } else try (JarFile jar = new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
                        var e = jar.getJarEntry(entry);
                        if (e == null) return UNKNOWN;
                        try (InputStream in = jar.getInputStream(e)) { declarations(in, name, shape); }
                    }
                }
                return shape;
            } catch (IOException | URISyntaxException | RuntimeException ex) {
                warn(name, "无法确认继承声明，保留原名：" + ex);
                return UNKNOWN;
            }
        }
    };

    private void warn(String key, String reason) {
        if (warnings.add(key)) LOG.log(System.Logger.Level.WARNING, "游戏反射名称 " + key + "：" + reason);
    }

    private static String validName(String name, boolean method) {
        if (method && (name.equals("<init>") || name.equals("<clinit>"))) return name;
        if (!name.isEmpty() && name.chars().noneMatch(c -> ".;[/".indexOf(c) >= 0 || method && (c == '<' || c == '>'))) return name;
        try {
            return "$sso$" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(name.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
