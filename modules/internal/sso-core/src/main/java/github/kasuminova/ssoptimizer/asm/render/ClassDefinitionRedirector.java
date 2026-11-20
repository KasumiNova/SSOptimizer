package github.kasuminova.ssoptimizer.asm.render;

import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.SerialVersionUIDAdder;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** 为 Launch 域中继承 JDK 类加载器的开放类集合改写标准类查找和定义调用。Mixin 无法预先枚举这些类。 */
public final class ClassDefinitionRedirector {
    private static final Logger LOGGER = Logger.getLogger(ClassDefinitionRedirector.class);
    private static final byte[] DEFINE_CLASS = "defineClass".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] FIND_CLASS = "findClass".getBytes(StandardCharsets.US_ASCII);
    private static final String SERIALIZABLE = "java/io/Serializable";
    private static final String URL_LOADER = "java/net/URLClassLoader";
    private static final String FIND_RESOURCE_DESC = "(Ljava/lang/String;)Ljava/net/URL;";
    private static final String FIND_CLASS_DESC = "(Ljava/lang/String;)Ljava/lang/Class;";
    private static final String LOADER = "java/lang/ClassLoader";
    private static final String SECURE_LOADER = "java/security/SecureClassLoader";
    private static final String BYTE_ARRAY_DEFINITION = "([BII)Ljava/lang/Class;";
    private static final String NAMED_DEFINITION = "(Ljava/lang/String;[BII)Ljava/lang/Class;";
    private static final String PROTECTED_DEFINITION = "(Ljava/lang/String;[BIILjava/security/ProtectionDomain;)Ljava/lang/Class;";
    private static final String BUFFER_DEFINITION = "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/ProtectionDomain;)Ljava/lang/Class;";
    private static final String SOURCE_DEFINITION = "(Ljava/lang/String;[BIILjava/security/CodeSource;)Ljava/lang/Class;";
    private static final String SOURCE_BUFFER_DEFINITION = "(Ljava/lang/String;Ljava/nio/ByteBuffer;Ljava/security/CodeSource;)Ljava/lang/Class;";
    private static final String BRIDGE_PREFIX = "github/kasuminova/ssoptimizer/bridge/opengl/";
    private static final Type OWNER = Type.getType(ClassDefinitionRedirector.class);
    private static final Type BYTE_SLICE = Type.getType(ByteSlice.class);
    private static final Type CLASS_LOADER = Type.getType(ClassLoader.class);
    private static final Type BYTE_BUFFER = Type.getType(ByteBuffer.class);
    private static final Type BYTE_ARRAY = Type.getType(byte[].class);
    private static final Type STRING = Type.getType(String.class);
    private static final Map<ClassLoader, Set<String>> GENERATED_LOOKUPS = new WeakHashMap<>();
    private static final Map<ClassLoader, Map<String, ClassHeader>> HEADERS = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> RESOLVING_BRIDGES = new ThreadLocal<>();

    private ClassDefinitionRedirector() { }

    public static byte[] rewrite(final byte[] bytes, final ClassLoader launchLoader) throws IOException {
        final ClassReader reader = new ClassReader(bytes);
        final Hierarchy hierarchy = loaderAncestors(reader.getSuperName(), launchLoader);
        final Set<String> ancestors = hierarchy.names();
        if (!ancestors.contains(LOADER)) return bytes;

        final boolean mayInheritLookup = ancestors.contains(URL_LOADER) && !hierarchy.customLookup();
        final boolean explicitDefinitions = references(bytes, DEFINE_CLASS) || references(bytes, FIND_CLASS);
        if (!mayInheritLookup && !explicitDefinitions) return bytes;

        final ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        final boolean serializable = mayInheritLookup && isSerializable(node.superName, node.interfaces, launchLoader);
        final boolean hasUidField = node.fields.stream().anyMatch(field -> field.name.equals("serialVersionUID"));
        final boolean injectLookup = mayInheritLookup && node.methods.stream()
                .noneMatch(method -> isUrlLookup(method.name, method.desc))
                && (!serializable || !hasUidField || node.fields.stream().anyMatch(field ->
                        field.name.equals("serialVersionUID") && field.desc.equals("J")
                                && (field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                                == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)));
        ancestors.add(node.name);
        final Set<String> names = new HashSet<>();
        node.methods.forEach(method -> names.add(method.name));
        final Map<Invocation, Method> helpers = new LinkedHashMap<>();
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !isDefinition(call, ancestors)) continue;
                if (injectLookup && call.getOpcode() == Opcodes.INVOKEVIRTUAL && call.name.equals("findClass")) continue;
                final Invocation invocation = new Invocation(call.getOpcode(), call.owner, call.name, call.desc);
                final Method helper = helpers.computeIfAbsent(invocation, ignored -> {
                    int suffix = helpers.size();
                    String name;
                    do {
                        name = "ssoptimizer$" + call.name + "$" + suffix++;
                    } while (!names.add(name));
                    final var args = new ArrayList<Type>();
                    // 跨包调用 protected 方法时，JVM 要求接收者属于发起调用的子类。
                    args.add(Type.getObjectType(node.name));
                    args.addAll(Arrays.asList(Type.getArgumentTypes(call.desc)));
                    return new Method(name, Type.getType(Class.class), args.toArray(Type[]::new));
                });
                method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, node.name,
                        helper.getName(), helper.getDescriptor(), false));
            }
        }
        if (!injectLookup && helpers.isEmpty()) return bytes;

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        helpers.forEach((invocation, helper) -> appendHelper(writer, invocation, helper));
        if (injectLookup) {
            if (serializable && !hasUidField) {
                final var uid = new SerialVersionUIDAdder(Opcodes.ASM9, null) {
                    @Override
                    protected void addSVUID(final long value) {
                        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                                "serialVersionUID", "J", null, value).visitEnd();
                    }
                };
                reader.accept(uid, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
            appendLookupOverride(writer, node.name, node.superName);
        }
        final byte[] rewritten = writer.toByteArray();
        if (injectLookup) {
            synchronized (GENERATED_LOOKUPS) {
                GENERATED_LOOKUPS.computeIfAbsent(launchLoader, ignored -> new HashSet<>()).add(node.name.replace('/', '.'));
            }
        }
        return rewritten;
    }

    static boolean generatedLookup(final Class<?> owner) {
        synchronized (GENERATED_LOOKUPS) {
            final Set<String> names = GENERATED_LOOKUPS.get(owner.getClassLoader());
            return names != null && names.contains(owner.getName());
        }
    }

    private static boolean references(final byte[] bytes, final byte[] target) {
        for (int from = 0; from <= bytes.length - target.length; from++) {
            if (Arrays.equals(bytes, from, from + target.length, target, 0, target.length)) return true;
        }
        return false;
    }

    private static Hierarchy loaderAncestors(String type, final ClassLoader launchLoader) throws IOException {
        final Set<String> ancestors = new HashSet<>();
        boolean customLookup = false;
        while (type != null && !"java/lang/Object".equals(type) && ancestors.add(type)) {
            if (type.equals(LOADER)) break;
            if (type.equals(URL_LOADER)) {
                ancestors.add(SECURE_LOADER);
                ancestors.add(LOADER);
                break;
            }
            final ClassHeader header = readHeader(type, launchLoader);
            if (header == null) break;
            customLookup |= header.customLookup();
            type = header.superName();
        }
        return new Hierarchy(ancestors, customLookup);
    }

    private static ClassHeader readHeader(final String type, final ClassLoader launchLoader) throws IOException {
        final Map<String, ClassHeader> headers;
        synchronized (HEADERS) {
            headers = HEADERS.computeIfAbsent(launchLoader, ignored -> new ConcurrentHashMap<>());
        }
        final ClassHeader cached = headers.get(type);
        if (cached != null) return cached;
        // 读取资源中的继承关系，避免在 Launch transformer 内触发类定义。
        try (var in = launchLoader.getResourceAsStream(type + ".class")) {
            if (in == null) return null;
            final ClassReader parent = new ClassReader(in);
            final boolean[] customLookup = {false};
            parent.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (isUrlLookup(name, desc)) customLookup[0] = true;
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            final ClassHeader header = new ClassHeader(parent.getSuperName(), parent.getInterfaces(), customLookup[0]);
            headers.putIfAbsent(type, header);
            return header;
        }
    }

    private static boolean isSerializable(final String superName, final List<String> interfaces,
                                          final ClassLoader launchLoader) throws IOException {
        final var pending = new ArrayList<>(interfaces);
        if (superName != null) pending.add(superName);
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < pending.size(); i++) {
            final String type = pending.get(i);
            if (type.equals(SERIALIZABLE)) return true;
            if (type.equals(URL_LOADER) || type.equals("java/lang/Object") || !seen.add(type)) continue;
            final ClassHeader header = readHeader(type, launchLoader);
            if (header == null) return true;
            if (header.superName() != null) pending.add(header.superName());
            pending.addAll(Arrays.asList(header.interfaces()));
        }
        return false;
    }

    private static boolean isUrlLookup(final String name, final String descriptor) {
        return name.equals("findClass") && descriptor.equals(FIND_CLASS_DESC)
                || name.equals("findResource") && descriptor.equals(FIND_RESOURCE_DESC);
    }

    private static boolean isDefinition(final MethodInsnNode call, final Set<String> ancestors) {
        if (!ancestors.contains(call.owner)
                || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL)) {
            return false;
        }
        if ("findClass".equals(call.name)) {
            return ancestors.contains(URL_LOADER) && FIND_CLASS_DESC.equals(call.desc);
        }
        if (!"defineClass".equals(call.name)) return false;
        return switch (call.desc) {
            case BYTE_ARRAY_DEFINITION, NAMED_DEFINITION, PROTECTED_DEFINITION, BUFFER_DEFINITION -> true;
            case SOURCE_DEFINITION, SOURCE_BUFFER_DEFINITION -> ancestors.contains(SECURE_LOADER) && !call.owner.equals(LOADER);
            default -> false;
        };
    }

    private static void appendHelper(final ClassWriter writer, final Invocation call, final Method helper) {
        final int access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        final GeneratorAdapter mv = new GeneratorAdapter(access, helper,
                writer.visitMethod(access, helper.getName(), helper.getDescriptor(), null, null));
        mv.visitCode();
        if ("findClass".equals(call.name)) {
            appendFindClass(mv, call);
            mv.endMethod();
            return;
        }
        final boolean named = !call.descriptor.equals(BYTE_ARRAY_DEFINITION);
        final int data = named ? 2 : 1;
        final boolean buffer = call.descriptor.equals(BUFFER_DEFINITION) || call.descriptor.equals(SOURCE_BUFFER_DEFINITION);
        final boolean sourced = call.descriptor.equals(SOURCE_DEFINITION) || call.descriptor.equals(SOURCE_BUFFER_DEFINITION);
        final boolean protectedDomain = call.descriptor.equals(PROTECTED_DEFINITION) || call.descriptor.equals(BUFFER_DEFINITION);
        final boolean hasSource = sourced || protectedDomain;
        final int domain = data + (buffer ? 1 : 3);
        mv.loadArg(0);
        if (named) mv.loadArg(1);
        else mv.visitInsn(Opcodes.ACONST_NULL);
        mv.loadArg(data);
        final int rewritten;
        final Type sourceType = Type.getType(CodeSource.class);
        if (buffer) {
            if (hasSource) loadDefinitionSource(mv, domain, protectedDomain);
            final Type[] arguments = hasSource ? new Type[]{CLASS_LOADER, STRING, BYTE_BUFFER, sourceType}
                    : new Type[]{CLASS_LOADER, STRING, BYTE_BUFFER};
            mv.invokeStatic(OWNER, new Method("redirectBuffer", BYTE_BUFFER, arguments));
            rewritten = mv.newLocal(BYTE_BUFFER);
        } else {
            mv.loadArg(data + 1);
            mv.loadArg(data + 2);
            if (hasSource) loadDefinitionSource(mv, domain, protectedDomain);
            final Type[] arguments = hasSource ? new Type[]{CLASS_LOADER, STRING, BYTE_ARRAY, Type.INT_TYPE, Type.INT_TYPE, sourceType}
                    : new Type[]{CLASS_LOADER, STRING, BYTE_ARRAY, Type.INT_TYPE, Type.INT_TYPE};
            mv.invokeStatic(OWNER, new Method("redirectSlice", BYTE_SLICE, arguments));
            rewritten = mv.newLocal(BYTE_SLICE);
        }
        mv.storeLocal(rewritten);
        mv.loadArg(0);
        if (named) mv.loadArg(1);
        mv.loadLocal(rewritten);
        if (!buffer) {
            mv.invokeVirtual(BYTE_SLICE, new Method("bytes", BYTE_ARRAY, new Type[0]));
            mv.loadLocal(rewritten);
            mv.invokeVirtual(BYTE_SLICE, new Method("offset", Type.INT_TYPE, new Type[0]));
            mv.loadLocal(rewritten);
            mv.invokeVirtual(BYTE_SLICE, new Method("length", Type.INT_TYPE, new Type[0]));
        }
        if (domain < helper.getArgumentTypes().length) mv.loadArg(domain);
        mv.visitMethodInsn(call.opcode, call.owner, "defineClass", call.descriptor, false);
        mv.returnValue();
        mv.endMethod();
    }

    private static void loadDefinitionSource(final GeneratorAdapter mv, final int argument, final boolean protectedDomain) {
        mv.loadArg(argument);
        if (protectedDomain) {
            mv.invokeStatic(OWNER, new Method("definitionSource", Type.getType(CodeSource.class),
                    new Type[]{Type.getType(ProtectionDomain.class)}));
        }
    }

    public static CodeSource definitionSource(final ProtectionDomain domain) {
        return UrlClassDefinition.hasScopedNormalizer() && domain != null ? domain.getCodeSource() : null;
    }

    private static void appendLookupOverride(final ClassWriter writer, final String owner, final String superName) {
        final int access = Opcodes.ACC_PROTECTED | Opcodes.ACC_SYNTHETIC;
        final Method method = new Method("findClass", FIND_CLASS_DESC);
        final var visitor = writer.visitMethod(access, method.getName(), method.getDescriptor(), null,
                new String[]{"java/lang/ClassNotFoundException"});
        final GeneratorAdapter mv = new GeneratorAdapter(access, method, visitor);
        mv.visitCode();
        appendFindClass(mv, new Invocation(Opcodes.INVOKESPECIAL, superName, method.getName(), method.getDescriptor()), owner);
        mv.endMethod();
    }

    private static void appendFindClass(final GeneratorAdapter mv, final Invocation call) {
        appendFindClass(mv, call, null);
    }

    private static void appendFindClass(final GeneratorAdapter mv, final Invocation call, final String instanceOwner) {
        final Type lookup = Type.getType(MethodHandles.Lookup.class);
        final Type result = Type.getType(Class.class);
        loadLookupArguments(mv, instanceOwner);
        mv.invokeStatic(Type.getType(MethodHandles.class), new Method("lookup", lookup, new Type[0]));
        mv.push(instanceOwner != null);
        mv.invokeStatic(Type.getType(UrlClassDefinition.class), new Method("findClass", result,
                new Type[]{Type.getType(URLClassLoader.class), STRING, lookup, Type.BOOLEAN_TYPE}));
        mv.dup();
        final Label original = mv.newLabel();
        mv.ifNull(original);
        mv.returnValue();
        mv.mark(original);
        final String receiver = instanceOwner == null ? mv.getArgumentTypes()[0].getInternalName() : instanceOwner;
        mv.visitFrame(Opcodes.F_NEW, 2, new Object[]{receiver, STRING.getInternalName()},
                1, new Object[]{"java/lang/Class"});
        mv.pop();
        loadLookupArguments(mv, instanceOwner);
        mv.visitMethodInsn(call.opcode, call.owner, call.name, call.descriptor, false);
        mv.returnValue();
    }

    private static void loadLookupArguments(final GeneratorAdapter mv, final String instanceOwner) {
        if (instanceOwner == null) {
            mv.loadArg(0);
            mv.loadArg(1);
        } else {
            mv.loadThis();
            mv.loadArg(0);
        }
    }

    public static ByteSlice redirectSlice(final ClassLoader loader, final String name, final byte[] bytes,
                                          final int offset, final int length) {
        return redirectSlice(loader, name, bytes, offset, length, null);
    }

    public static ByteSlice redirectSlice(final ClassLoader loader, final String name, final byte[] bytes,
                                          final int offset, final int length, final CodeSource source) {
        if (loader != null && bytes != null && offset >= 0 && length >= 0 && offset <= bytes.length - length) {
            final byte[] input = offset == 0 && length == bytes.length ? bytes : Arrays.copyOfRange(bytes, offset, offset + length);
            final byte[] normalized = UrlClassDefinition.normalizeFromArchive(source, name, input);
            final byte[] rewritten = UrlClassDefinition.redirectExplicit(source) ? redirect(loader, name, normalized) : normalized;
            if (rewritten != input) return new ByteSlice(rewritten, 0, rewritten.length);
        }
        return new ByteSlice(bytes, offset, length);
    }

    public static ByteBuffer redirectBuffer(final ClassLoader loader, final String name, final ByteBuffer bytes) {
        return redirectBuffer(loader, name, bytes, null);
    }

    public static ByteBuffer redirectBuffer(final ClassLoader loader, final String name, final ByteBuffer bytes,
                                            final CodeSource source) {
        if (loader == null || bytes == null || (!bytes.isDirect() && !bytes.hasArray())) return bytes;
        // JDK defineClass 会推进无可访问数组的非直接缓冲区；保留该调用及其位置变化。
        final byte[] input = new byte[bytes.remaining()];
        bytes.duplicate().get(input);
        final byte[] normalized = UrlClassDefinition.normalizeFromArchive(source, name, input);
        final byte[] rewritten = UrlClassDefinition.redirectExplicit(source) ? redirect(loader, name, normalized) : normalized;
        return rewritten == input ? bytes : ByteBuffer.wrap(rewritten);
    }

    static byte[] redirect(final ClassLoader loader, final String name, final byte[] bytes) {
        if (RESOLVING_BRIDGES.get() != null) return bytes;
        RESOLVING_BRIDGES.set(Boolean.TRUE);
        try {
            final byte[] rewritten = RenderThreadRedirector.redirect(name, bytes);
            if (rewritten == bytes) return bytes;
            final Set<String> dependencies = new HashSet<>();
            new ClassReader(rewritten).accept(new ClassRemapper(new ClassWriter(0), new Remapper() {
                @Override
                public String map(final String type) {
                    if (type.startsWith(BRIDGE_PREFIX)) dependencies.add(type.replace('/', '.'));
                    return type;
                }
            }), 0);
            final ClassLoader bridgeLoader = ClassDefinitionRedirector.class.getClassLoader();
            for (String dependency : dependencies) {
                final Class<?> bridge = Class.forName(dependency, false, bridgeLoader);
                if (Class.forName(dependency, false, loader) != bridge
                        || !Modifier.isPublic(bridge.getModifiers())
                        || !bridge.getModule().isExported(bridge.getPackageName(), loader.getUnnamedModule())) {
                    LOGGER.debug("GL 重定向保留原字节：定义域无法访问 " + dependency);
                    return bytes;
                }
            }
            return rewritten;
        } catch (ClassNotFoundException | LinkageError | RuntimeException ex) {
            LOGGER.debug("GL 重定向保留原字节：" + name, ex);
            return bytes;
        } finally {
            RESOLVING_BRIDGES.remove();
        }
    }

    public record ByteSlice(byte[] bytes, int offset, int length) { }

    private record Hierarchy(Set<String> names, boolean customLookup) { }

    private record ClassHeader(String superName, String[] interfaces, boolean customLookup) { }

    private record Invocation(int opcode, String owner, String name, String descriptor) { }
}
