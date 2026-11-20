package github.kasuminova.ssoptimizer.standalone.obf;

import java.nio.charset.StandardCharsets;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** 为 JDK 25 准备原始类字节。所有转换仅依赖输入字节。 */
public final class ObfClasses {
    static final String ORIGINAL_FIELD = "Lgithub/kasuminova/ssoptimizer/standalone/obf/OriginalField;";
    private ObfClasses() {}

    /** 返回 JVM 可表达的字段或方法名。合法名原样返回，两个初始化方法名只在方法位置保留。 */
    public static String memberName(String name, boolean method) {
        if (!invalid(name, method)) return name;
        return "$sso$" + Digests.bytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static boolean invalid(String name, boolean method) {
        if (method && (name.equals("<init>") || name.equals("<clinit>"))) return false;
        if (name.isEmpty()) return true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '.' || c == ';' || c == '[' || c == '/' || method && (c == '<' || c == '>')) return true;
        }
        return false;
    }

    /** 规范化类内成员、对外成员引用和调试名。合法字节返回原引用，损坏输入抛出异常。 */
    public static byte[] normalize(byte[] input) {
        return normalize(input, null, null);
    }

    /** 按需读取游戏声明，可在同一定义域内跨线程复用。 */
    public static EntryNormalizer entriesFor(java.util.List<java.nio.file.Path> originalGameJars) {
        var members = new GameMembers(originalGameJars);
        return (name, bytes) -> {
            try {
                return normalize(bytes, name, members);
            } catch (SecurityException | java.io.UncheckedIOException | IllegalArgumentException failure) {
                throw failure;
            } catch (RuntimeException malformed) {
                throw new IllegalArgumentException("无法解析类 " + name, malformed);
            }
        };
    }

    @FunctionalInterface
    public interface EntryNormalizer {
        /** 按请求的 JVM 内部类名读取条目。无改动时返回输入，损坏时抛出格式异常。 */
        byte[] apply(String internalName, byte[] bytes);
    }

    public static java.util.function.UnaryOperator<byte[]> forGameJars(java.util.List<java.nio.file.Path> originalGameJars) throws java.io.IOException {
        var entries = entriesFor(originalGameJars);
        return bytes -> entries.apply(null, bytes);
    }

    static byte[] normalize(byte[] input, String expected, GameMembers members) {
        var reader = new ClassReader(input);
        if (expected != null && !expected.equals(reader.getClassName())) throw new IllegalArgumentException("类名与条目不匹配 " + reader.getClassName());
        var node = new ClassNode();
        if (members == null) {
            members = new GameMembers();
            members.index(reader);
        }
        var names = new Names();
        reader.accept(new ClassRemapper(node, names) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                var field = super.visitField(access, name, descriptor, signature, value);
                if (invalid(name, false)) {
                    var marker = field.visitAnnotation(ORIGINAL_FIELD, false);
                    marker.visit("value", name);
                    marker.visitEnd();
                }
                return field;
            }
        }, 0);
        boolean changed = names.changed || staleConstants(reader);
        for (var method : node.methods) {
            if (method.localVariables != null) for (var local : method.localVariables) {
                if (invalid(local.name, false)) { local.name = "local" + local.index; changed = true; }
            }
            if (method.parameters != null) for (var param : method.parameters) {
                if (param.name != null && invalid(param.name, false)) { param.name = null; changed = true; }
            }
            changed |= MemberArguments.rewrite(node.name, method, members);
        }
        if (!changed) return input;
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean staleConstants(ClassReader reader) {
        char[] buffer = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            if (offset == 0 || reader.readByte(offset - 1) != 12) continue;
            String descriptor = reader.readUTF8(offset + 2, buffer);
            if (invalid(reader.readUTF8(offset, buffer), descriptor.startsWith("("))) return true;
        }
        return false;
    }

    private static final class Names extends Remapper {
        boolean changed;
        Names() { super(Opcodes.ASM9); }
        private String apply(String name, boolean method) {
            String fixed = memberName(name, method);
            changed |= !fixed.equals(name);
            return fixed;
        }
        @Override public String mapMethodName(String owner, String name, String desc) { return apply(name, true); }
        @Override public String mapFieldName(String owner, String name, String desc) { return apply(name, false); }
        @Override public String mapInvokeDynamicMethodName(String name, String desc, Handle bootstrap, Object... arguments) { return apply(name, true); }
        @Override public String mapRecordComponentName(String owner, String name, String desc) { return apply(name, false); }
        @Override public String mapAnnotationAttributeName(String descriptor, String name) { return name == null ? null : apply(name, true); }
    }
}
