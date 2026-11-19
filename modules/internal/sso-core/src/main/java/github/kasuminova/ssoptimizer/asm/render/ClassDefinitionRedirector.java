package github.kasuminova.ssoptimizer.asm.render;

import org.apache.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 为 Launch 域中继承 JDK 类加载器的开放类集合改写标准 defineClass 调用。Mixin 无法预先枚举这些类。 */
public final class ClassDefinitionRedirector {
    private static final Logger LOGGER = Logger.getLogger(ClassDefinitionRedirector.class);
    private static final byte[] DEFINE_CLASS = "defineClass".getBytes(StandardCharsets.US_ASCII);
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
    private static final ThreadLocal<Boolean> RESOLVING_BRIDGES = new ThreadLocal<>();

    private ClassDefinitionRedirector() { }

    public static byte[] rewrite(final byte[] bytes, final ClassLoader launchLoader) throws IOException {
        if (!referencesDefineClass(bytes)) return bytes;
        final ClassReader reader = new ClassReader(bytes);
        final Set<String> ancestors = loaderAncestors(reader.getSuperName(), launchLoader);
        if (!ancestors.contains(LOADER)) return bytes;

        final ClassNode node = new ClassNode(Opcodes.ASM9);
        reader.accept(node, 0);
        ancestors.add(node.name);
        final Set<String> names = new HashSet<>();
        node.methods.forEach(method -> names.add(method.name));
        final Map<Invocation, Method> helpers = new LinkedHashMap<>();
        for (var method : node.methods) {
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !isDefinition(call, ancestors)) continue;
                final Invocation invocation = new Invocation(call.getOpcode(), call.owner, call.desc);
                final Method helper = helpers.computeIfAbsent(invocation, ignored -> {
                    int suffix = helpers.size();
                    String name;
                    do {
                        name = "ssoptimizer$defineClass$" + suffix++;
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
        if (helpers.isEmpty()) return bytes;

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        helpers.forEach((invocation, helper) -> appendHelper(writer, invocation, helper));
        return writer.toByteArray();
    }

    private static boolean referencesDefineClass(final byte[] bytes) {
        for (int from = 0; from <= bytes.length - DEFINE_CLASS.length; from++) {
            if (Arrays.equals(bytes, from, from + DEFINE_CLASS.length, DEFINE_CLASS, 0, DEFINE_CLASS.length)) return true;
        }
        return false;
    }

    private static Set<String> loaderAncestors(String type, final ClassLoader launchLoader) throws IOException {
        final Set<String> ancestors = new HashSet<>();
        while (type != null && !"java/lang/Object".equals(type) && ancestors.add(type)) {
            if (type.equals(LOADER)) break;
            // 通过类资源读取继承关系；触发类定义会重入 Launch transformer。
            try (var in = launchLoader.getResourceAsStream(type + ".class")) {
                if (in == null) break;
                type = new ClassReader(in).getSuperName();
            }
        }
        return ancestors;
    }

    private static boolean isDefinition(final MethodInsnNode call, final Set<String> ancestors) {
        if (!"defineClass".equals(call.name) || !ancestors.contains(call.owner)
                || (call.getOpcode() != Opcodes.INVOKEVIRTUAL && call.getOpcode() != Opcodes.INVOKESPECIAL)) {
            return false;
        }
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
        final boolean named = !call.descriptor.equals(BYTE_ARRAY_DEFINITION);
        final int data = named ? 2 : 1;
        final boolean buffer = call.descriptor.equals(BUFFER_DEFINITION) || call.descriptor.equals(SOURCE_BUFFER_DEFINITION);
        mv.loadArg(0);
        if (named) mv.loadArg(1);
        else mv.visitInsn(Opcodes.ACONST_NULL);
        mv.loadArg(data);
        final int rewritten;
        if (buffer) {
            mv.invokeStatic(OWNER, new Method("redirectBuffer", BYTE_BUFFER, new Type[]{CLASS_LOADER, STRING, BYTE_BUFFER}));
            rewritten = mv.newLocal(BYTE_BUFFER);
        } else {
            mv.loadArg(data + 1);
            mv.loadArg(data + 2);
            mv.invokeStatic(OWNER, new Method("redirectSlice", BYTE_SLICE,
                    new Type[]{CLASS_LOADER, STRING, BYTE_ARRAY, Type.INT_TYPE, Type.INT_TYPE}));
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
        final int domain = data + (buffer ? 1 : 3);
        if (domain < helper.getArgumentTypes().length) mv.loadArg(domain);
        mv.visitMethodInsn(call.opcode, call.owner, "defineClass", call.descriptor, false);
        mv.returnValue();
        mv.endMethod();
    }

    public static ByteSlice redirectSlice(final ClassLoader loader, final String name, final byte[] bytes,
                                          final int offset, final int length) {
        if (loader != null && bytes != null && offset >= 0 && length >= 0 && offset <= bytes.length - length) {
            final byte[] input = offset == 0 && length == bytes.length ? bytes : Arrays.copyOfRange(bytes, offset, offset + length);
            final byte[] rewritten = redirect(loader, name, input);
            if (rewritten != input) return new ByteSlice(rewritten, 0, rewritten.length);
        }
        return new ByteSlice(bytes, offset, length);
    }

    public static ByteBuffer redirectBuffer(final ClassLoader loader, final String name, final ByteBuffer bytes) {
        if (loader == null || bytes == null || (!bytes.isDirect() && !bytes.hasArray())) return bytes;
        // JDK defineClass 会推进无可访问数组的非直接缓冲区；保留该调用及其位置变化。
        final byte[] input = new byte[bytes.remaining()];
        bytes.duplicate().get(input);
        final byte[] rewritten = redirect(loader, name, input);
        return rewritten == input ? bytes : ByteBuffer.wrap(rewritten);
    }

    private static byte[] redirect(final ClassLoader loader, final String name, final byte[] bytes) {
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

    private record Invocation(int opcode, String owner, String descriptor) { }
}
