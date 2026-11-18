package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** 在启动前按实际归档验证预先构建的 named 模组对游戏类及成员的静态链接。 */
public final class NamedCompatibility {
    private static final String MAPPING = "SSOptimizer-Mapping-SHA256";
    private static final String ABI = "SSOptimizer-Remap-ABI";
    private static final System.Logger LOG = System.getLogger(NamedCompatibility.class.getName());
    private static final int API = Opcodes.ASM9;

    private NamedCompatibility() { }

    /**
     * namedGame 为本轮准备完成的游戏产物，namedMods 为需要检查的原件。dependencies 可传完整、
     * 有序的运行时类路径；仅提供附加依赖时，按游戏、依赖、模组的顺序查找。
     * 并发调用各自建立只读索引，不加载或执行模组类。标记不匹配、游戏符号缺失或调用形状错误时抛出 IOException。
     * XML、Mixin 选择器和由字符串计算的反射目标须在相应运行场景中验证。
     */
    public static void verify(List<Path> namedGame, List<Path> namedMods, List<Path> dependencies) throws IOException {
        if (namedMods.isEmpty()) return;
        Set<Path> game = realPaths(namedGame);
        if (game.isEmpty()) throw new IOException("named ABI 校验缺少游戏归档");
        Set<Path> mods = realPaths(namedMods);
        Set<Path> deps = realPaths(dependencies);
        OriginMarkers expected = null;
        for (Path path : game) {
            OriginMarkers markers = OriginMarkers.read(path);
            if (expected != null && !expected.equals(markers)) throw new IOException("named 游戏映射或 ABI 不一致: " + path);
            expected = markers;
        }
        for (Path path : mods) OriginMarkers.read(path).verifyAgainst(expected, path);
        Set<Path> order = new LinkedHashSet<>();
        if (deps.containsAll(game) && deps.containsAll(mods)) order.addAll(deps);
        order.addAll(game);
        order.addAll(deps);
        order.addAll(mods);
        Index index = new Index(game);
        for (Path path : order) scan(path, reader -> index.add(reader, path));
        for (Path path : mods) {
            scan(path, reader -> {
                Header header = index.types.get(reader.getClassName());
                if (path.equals(header.origin)) reader.accept(new References(index, header), ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            });
        }
        LOG.log(System.Logger.Level.INFO, "已验证 {0} 个 named 归档的游戏字节码引用；动态反射目标不在静态校验范围内", mods.size());
    }

    private static Set<Path> realPaths(List<Path> input) throws IOException {
        Set<Path> out = new LinkedHashSet<>();
        for (Path path : input) out.add(path.toRealPath());
        return out;
    }

    private record OriginMarkers(String namespace, String mapping, String abi) {
        static OriginMarkers read(Path archive) throws IOException {
            try (JarFile jar = open(archive)) {
                var manifest = jar.getManifest();
                Attributes attr = manifest == null ? new Attributes() : manifest.getMainAttributes();
                String ns = attr.getValue("SSOptimizer-Namespace");
                if (ns != null && !ns.equals("named")) throw new IOException("预期 named 归档，命名空间为 " + ns + ": " + archive);
                return new OriginMarkers(ns, attr.getValue(MAPPING), attr.getValue(ABI));
            }
        }

        void verifyAgainst(OriginMarkers game, Path source) throws IOException {
            if (mapping != null && !mapping.equalsIgnoreCase(game.mapping)) {
                throw new IOException(source + ": " + MAPPING + "=" + mapping + "，当前值=" + game.mapping);
            }
            if (abi != null && !abi.equals(game.abi)) {
                throw new IOException(source + ": " + ABI + "=" + abi + "，当前值=" + game.abi);
            }
        }
    }

    private static JarFile open(Path path) throws IOException {
        return new JarFile(path.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
    }

    private static void scan(Path path, java.util.function.Consumer<ClassReader> visitor) throws IOException {
        try (JarFile jar = open(path); var entries = jar.versionedStream()) {
            for (var iter = entries.iterator(); iter.hasNext();) {
                var entry = iter.next();
                String name = entry.getName();
                if (!name.endsWith(".class") || name.equals("module-info.class") || name.startsWith("META-INF/")) continue;
                try (InputStream in = jar.getInputStream(entry)) {
                    visitor.accept(new ClassReader(in));
                } catch (RuntimeException e) {
                    throw new IOException(path + "!/" + name + ": " + e.getMessage(), e);
                }
            }
        }
    }

    private record Member(String name, String desc) { }
    private record Declaration(Header owner, int access) { }

    private static final class Header extends ClassVisitor {
        final Path origin;
        String name, parent, nestHost;
        List<String> interfaces, nestMembers;
        int access;
        final Map<Member, Integer> fields = new HashMap<>(), methods = new HashMap<>();

        Header(ClassReader reader, Path origin) {
            super(API);
            this.origin = origin;
            reader.accept(this, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }

        @Override public void visit(int version, int flags, String name, String signature, String parent, String[] interfaces) {
            this.name = name; this.access = flags; this.parent = parent; this.interfaces = List.of(interfaces);
            this.nestHost = name;
        }
        @Override public void visitNestHost(String host) { nestHost = host; }
        @Override public void visitNestMember(String member) {
            if (nestMembers == null) nestMembers = new ArrayList<>();
            nestMembers.add(member);
        }
        @Override public FieldVisitor visitField(int flags, String name, String desc, String sig, Object value) {
            fields.put(new Member(name, desc), flags); return null;
        }
        @Override public MethodVisitor visitMethod(int flags, String name, String desc, String sig, String[] ex) {
            methods.put(new Member(name, desc), flags); return null;
        }
        boolean isInterface() { return (access & Opcodes.ACC_INTERFACE) != 0; }
        boolean isPublic() { return (access & Opcodes.ACC_PUBLIC) != 0; }
    }

    private static final class Index {
        final Map<String, Header> types = new HashMap<>();
        final Set<String> missing = new HashSet<>(), packages = new HashSet<>();
        final Map<String, Boolean> related = new HashMap<>();
        final Set<Path> game;

        Index(Set<Path> game) { this.game = game; }
        void add(ClassReader reader, Path path) {
            if (game.contains(path)) packages.add(pkg(reader.getClassName()));
            types.computeIfAbsent(reader.getClassName(), n -> new Header(reader, path));
        }
        Header get(String name) {
            Header h = types.get(name);
            if (h != null || !missing.add(name)) return h;
            try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(name + ".class")) {
                if (in == null) return null;
                h = new Header(new ClassReader(in), null);
                types.put(name, h);
                return h;
            } catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        }
        boolean related(Header owner) {
            Boolean known = related.get(owner.name);
            if (known != null) return known;
            boolean result = ancestry(owner).stream().anyMatch(h -> h.origin != null && game.contains(h.origin));
            related.put(owner.name, result);
            return result;
        }
        boolean gameNamespace(String name) {
            return packages.contains(pkg(name)) || name.startsWith("com/fs/starfarer/") || name.startsWith("com/fs/graphics/")
                    || name.startsWith("com/fs/util/");
        }
        List<Header> ancestry(Header from) {
            var visited = new LinkedHashSet<Header>();
            var queue = new ArrayDeque<Header>(); queue.add(from);
            while (!queue.isEmpty()) {
                Header h = queue.remove();
                if (!visited.add(h)) continue;
                if (h.parent != null && get(h.parent) != null) queue.add(get(h.parent));
                for (String face : h.interfaces) if (get(face) != null) queue.add(get(face));
            }
            return List.copyOf(visited);
        }
        boolean subtype(Header child, Header parent) { return child == parent || ancestry(child).contains(parent); }
        boolean nestmates(Header a, Header b) {
            if (a == b) return true;
            if (!a.nestHost.equals(b.nestHost) || !pkg(a.name).equals(pkg(b.name))) return false;
            Header host = get(a.nestHost);
            return host != null && host.nestMembers != null && (a == host || host.nestMembers.contains(a.name))
                    && (b == host || host.nestMembers.contains(b.name));
        }
        void verifyOverrides(Header child) {
            if (child.isInterface() || !related(child)) return;
            List<Header> parents = ancestry(child);
            for (Header parent : parents) {
                if (parent == child || parent.origin == null || !game.contains(parent.origin)) continue;
                for (var entry : parent.methods.entrySet()) {
                    Member method = entry.getKey();
                    int flags = entry.getValue();
                    if (method.name.startsWith("<") || (flags & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue;
                    if ((flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) == 0 && !pkg(parent.name).equals(pkg(child.name))) continue;
                    Integer override = child.methods.get(method);
                    if (override != null && (override & Opcodes.ACC_PRIVATE) == 0
                            && ((flags & Opcodes.ACC_FINAL) != 0 || (override & Opcodes.ACC_STATIC) != 0)) {
                        throw new IllegalArgumentException(child.name + " 不能覆盖 " + parent.name + "." + method.name + method.desc);
                    }
                    if ((child.access & Opcodes.ACC_ABSTRACT) != 0 || (flags & Opcodes.ACC_ABSTRACT) == 0) continue;
                    Declaration resolved = method(child, method);
                    if (resolved == null || (resolved.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0
                            || (parent.isInterface() && (resolved.access & Opcodes.ACC_PUBLIC) == 0)) {
                        throw new IllegalArgumentException(child.name + " 缺少游戏抽象方法实现: " + parent.name + "." + method.name + method.desc);
                    }
                }
            }
        }
        Declaration field(Header owner, Member member, Set<Header> visited) {
            if (owner == null || !visited.add(owner)) return null;
            Integer flags = owner.fields.get(member);
            if (flags != null) return new Declaration(owner, flags);
            for (String face : owner.interfaces) {
                Declaration found = field(get(face), member, visited);
                if (found != null) return found;
            }
            return owner.isInterface() || owner.parent == null ? null : field(get(owner.parent), member, visited);
        }
        Declaration method(Header owner, Member key) {
            Integer direct = owner.methods.get(key);
            if (direct != null) return new Declaration(owner, direct);
            if (key.name.equals("<init>") || key.name.equals("<clinit>")) return null;
            if (owner.isInterface()) {
                var object = get("java/lang/Object");
                Integer flags = object.methods.get(key);
                if (flags != null && (flags & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC) {
                    return new Declaration(object, flags);
                }
            } else {
                Set<Header> seen = new HashSet<>();
                for (Header h = owner; h != null && seen.add(h); h = h.parent == null ? null : get(h.parent)) {
                    Integer flags = h.methods.get(key);
                    if (flags != null) return new Declaration(h, flags);
                }
            }
            Map<Header, Integer> matches = new LinkedHashMap<>();
            for (Header h : ancestry(owner)) {
                if (!h.isInterface()) continue;
                Integer flags = h.methods.get(key);
                if (flags != null && (flags & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0) matches.put(h, flags);
            }
            var maximal = matches.keySet().stream().filter(h -> matches.keySet().stream().noneMatch(other -> other != h && subtype(other, h))).toList();
            var defaults = maximal.stream().filter(h -> (matches.get(h) & Opcodes.ACC_ABSTRACT) == 0).toList();
            if (defaults.size() > 1) throw new IllegalArgumentException("冲突的接口默认方法: " + owner.name + "." + key.name + key.desc);
            Header selected = defaults.isEmpty() ? (maximal.isEmpty() ? null : maximal.getFirst()) : defaults.getFirst();
            return selected == null ? null : new Declaration(selected, matches.get(selected));
        }
    }

    private static String pkg(String name) {
        int end = name.lastIndexOf('/');
        return end == -1 ? "" : name.substring(0, end);
    }

    private static final class References extends ClassVisitor {
        final Index index;
        final Header caller;
        String site;

        References(Index index, Header caller) { super(API); this.index = index; this.caller = caller; site = caller.name; }
        private IllegalArgumentException error(String detail) { return new IllegalArgumentException(site + ": " + detail); }
        private Header type(String name) { return type(name, true); }
        private Header type(String name, boolean checkAccess) {
            if (name.startsWith("[")) { inspect(Type.getType(name), checkAccess); return null; }
            Header h = index.get(name);
            if (h == null) {
                if (index.gameNamespace(name)) throw error("游戏类型不存在: " + name);
            } else if (checkAccess && h.origin != null && index.game.contains(h.origin) && !h.isPublic() && !pkg(caller.name).equals(pkg(name))) {
                throw error("游戏类型不可访问: " + name);
            }
            return h;
        }
        private void descriptor(String desc) { inspect(Type.getType(desc), false); }
        private void inspect(Type t, boolean checkAccess) {
            switch (t.getSort()) {
                case Type.OBJECT -> type(t.getInternalName(), checkAccess);
                case Type.ARRAY -> inspect(t.getElementType(), checkAccess);
                case Type.METHOD -> { for (Type arg : t.getArgumentTypes()) inspect(arg, checkAccess); inspect(t.getReturnType(), checkAccess); }
                default -> { }
            }
        }
        private void checkMember(String owner, String name, String desc, boolean field, boolean statik, Boolean iface, boolean write) {
            descriptor(desc);
            Header h = type(owner);
            if (h == null || !index.related(h)) return;
            String target = owner + "." + name + (field ? ":" : "") + desc;
            if (iface != null && iface != h.isInterface()) throw error("类/接口调用类型不匹配: " + target);
            var member = new Member(name, desc);
            Declaration value = field ? index.field(h, member, new HashSet<>()) : index.method(h, member);
            if (value == null) throw error((field ? "找不到字段: " : "找不到方法: ") + target);
            if (((value.access & Opcodes.ACC_STATIC) != 0) != statik) throw error("静态/实例调用不匹配: " + target);
            Header declared = value.owner;
            if ((value.access & Opcodes.ACC_PUBLIC) == 0 && caller != declared) {
                boolean accessible = (value.access & Opcodes.ACC_PRIVATE) != 0 ? index.nestmates(caller, declared)
                        : pkg(caller.name).equals(pkg(declared.name)) || ((value.access & Opcodes.ACC_PROTECTED) != 0 && index.subtype(caller, declared));
                if (!accessible) throw error("无法访问 " + target + "，声明于 " + declared.name);
            }
            if (write && (value.access & Opcodes.ACC_FINAL) != 0 && declared != caller) throw error("禁止写入其他类的 final 字段: " + target);
        }
        @Override public void visit(int v, int flags, String name, String sig, String parent, String[] interfaces) {
            if (parent != null) {
                Header base = type(parent);
                if (base != null && index.related(base) && (base.isInterface() || (base.access & Opcodes.ACC_FINAL) != 0)) throw error("非法父类: " + parent);
            }
            index.verifyOverrides(caller);
            for (String face : interfaces) {
                Header h = type(face);
                if (h != null && index.related(h) && !h.isInterface()) throw error("预期接口，实际是类: " + face);
            }
        }
        @Override public FieldVisitor visitField(int flags, String name, String desc, String sig, Object val) {
            site = caller.name + "." + name; descriptor(desc); return null;
        }
        @Override public MethodVisitor visitMethod(int flags, String name, String desc, String sig, String[] thrown) {
            site = caller.name + "." + name + desc;
            descriptor(desc);
            if (thrown != null) for (String ex : thrown) type(ex);
            return new MethodVisitor(API) {
                @Override public void visitFieldInsn(int op, String owner, String n, String d) {
                    boolean write = op == Opcodes.PUTSTATIC || op == Opcodes.PUTFIELD;
                    checkMember(owner, n, d, true, op == Opcodes.GETSTATIC || op == Opcodes.PUTSTATIC, null, write);
                }
                @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                    checkMember(owner, n, d, false, op == Opcodes.INVOKESTATIC, itf, false);
                }
                @Override public void visitTypeInsn(int op, String name) { type(name); }
                @Override public void visitMultiANewArrayInsn(String desc, int dims) { descriptor(desc); }
                @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end, org.objectweb.asm.Label handler, String caught) {
                    if (caught != null) type(caught);
                }
                @Override public void visitLdcInsn(Object value) { constant(value); }
                @Override public void visitInvokeDynamicInsn(String n, String d, Handle bootstrap, Object... args) {
                    descriptor(d); handle(bootstrap); for (Object arg : args) constant(arg);
                    if (bootstrap.getOwner().equals("java/lang/invoke/LambdaMetafactory") && (bootstrap.getName().equals("metafactory") || bootstrap.getName().equals("altMetafactory"))
                            && args.length > 0 && args[0] instanceof Type sam && Type.getReturnType(d).getSort() == Type.OBJECT) {
                        checkMember(Type.getReturnType(d).getInternalName(), n, sam.getDescriptor(), false, false, true, false);
                    }
                }
            };
        }
        private void constant(Object value) {
            if (value instanceof Type type) inspect(type, true);
            else if (value instanceof Handle handle) handle(handle);
            else if (value instanceof ConstantDynamic c) {
                descriptor(c.getDescriptor()); handle(c.getBootstrapMethod());
                for (int i = 0; i < c.getBootstrapMethodArgumentCount(); i++) constant(c.getBootstrapMethodArgument(i));
            }
        }
        private void handle(Handle h) {
            int tag = h.getTag();
            boolean field = tag >= Opcodes.H_GETFIELD && tag <= Opcodes.H_PUTSTATIC;
            checkMember(h.getOwner(), h.getName(), h.getDesc(), field,
                    tag == Opcodes.H_GETSTATIC || tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_INVOKESTATIC,
                    field ? null : h.isInterface(), tag == Opcodes.H_PUTSTATIC || tag == Opcodes.H_PUTFIELD);
        }
    }
}
