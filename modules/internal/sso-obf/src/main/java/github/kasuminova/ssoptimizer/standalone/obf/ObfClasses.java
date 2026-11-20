package github.kasuminova.ssoptimizer.standalone.obf;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;

/** 为 JDK 25 准备原始类字节。所有转换仅依赖输入字节。 */
public final class ObfClasses {
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
        return normalize(input, false, null, null);
    }

    /** 为同一游戏定义域创建可并发调用的转换函数。按挂载顺序读取原始游戏归档，读取失败抛出 IOException。 */
    public static java.util.function.UnaryOperator<byte[]> forGameJars(java.util.List<java.nio.file.Path> originalGameJars) throws java.io.IOException {
        var members = new GameMembers();
        for (var game : originalGameJars) members.index(game);
        return bytes -> normalize(bytes, false, null, members);
    }

    static byte[] normalize(byte[] input, boolean checkStructure, String expected, GameMembers members) {
        if (input.length < 10 || input[0] != (byte) 0xca || input[1] != (byte) 0xfe || input[2] != (byte) 0xba || input[3] != (byte) 0xbe) {
            throw new IllegalArgumentException("类文件头无效");
        }
        var reader = new ClassReader(input);
        int version = reader.readUnsignedShort(6);
        if (version < 45 || version > Runtime.version().feature() + 44) throw new IllegalArgumentException("不支持的类文件版本 " + version);
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
                    var marker = field.visitAnnotation("Lgithub/kasuminova/ssoptimizer/standalone/obf/OriginalField;", false);
                    marker.visit("value", name);
                    marker.visitEnd();
                }
                return field;
            }
        }, 0);
        boolean changed = names.changed || staleConstants(reader);
        var fields = new HashSet<String>();
        for (var field : node.fields) if (!fields.add(field.name + '\0' + field.desc)) throw new IllegalArgumentException("字段冲突 " + field.name + field.desc);
        var methods = new HashSet<String>();
        for (var method : node.methods) {
            if (!methods.add(method.name + '\0' + method.desc)) throw new IllegalArgumentException("方法冲突 " + method.name + method.desc);
            if (method.localVariables != null) for (var local : method.localVariables) {
                if (invalid(local.name, false)) { local.name = "local" + local.index; changed = true; }
            }
            if (method.parameters != null) for (var param : method.parameters) {
                if (param.name != null && invalid(param.name, false)) { param.name = null; changed = true; }
            }
            changed |= MemberArguments.rewrite(node.name, method, members);
        }
        if (!changed && !checkStructure) return input;
        var writer = new ClassWriter(0);
        node.accept(writer);
        byte[] result = changed ? writer.toByteArray() : input;
        if (checkStructure) {
            if ("module-info".equals(node.name) && node.module == null) throw new IllegalArgumentException("缺少 Module 属性");
            ModClassStructure.check(result, node);
        }
        return result;
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
