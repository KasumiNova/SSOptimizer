package github.kasuminova.ssoptimizer.standalone.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.JSRInlinerAdapter;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

final class BytecodeRepair {
    private BytecodeRepair() {
    }

    static String memberName(final String name, final boolean method) {
        if (method && (name.equals("<init>") || name.equals("<clinit>"))) {
            return name;
        }
        boolean invalid = name.isEmpty();
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            invalid |= c == '.' || c == ';' || c == '[' || c == '/' || (method && (c == '<' || c == '>'));
        }
        return invalid ? "$sso$" + Digests.bytes(name.getBytes(StandardCharsets.UTF_8)) : name;
    }

    static byte[] rewrite(final byte[] original, final ClassHierarchy hierarchy, final int release) {
        return rewrite(original, hierarchy, release, null);
    }

    static byte[] rewrite(final byte[] original, final ClassHierarchy hierarchy, final int release,
                          final MappingProjection mapping) {
        return rewrite(original, hierarchy, release, mapping, false);
    }

    static byte[] rewrite(final byte[] original, final ClassHierarchy hierarchy, final int release,
                          final MappingProjection mapping, boolean preserveFrames) {
        final ClassNode node = new ClassNode();
        new ClassReader(original).accept(new ClassRemapper(node, new Remapper(Opcodes.ASM9) {
            @Override
            public String mapMethodName(final String owner, final String name, final String descriptor) {
                return memberName(name, true);
            }

            @Override
            public String mapFieldName(final String owner, final String name, final String descriptor) {
                return memberName(name, false);
            }
        }), preserveFrames ? ClassReader.EXPAND_FRAMES : ClassReader.SKIP_FRAMES);
        if (mapping != null) {
            ClassNameCalls.rewrite(node, mapping);
        }
        final Set<String> fields = new HashSet<>();
        node.fields.forEach(field -> {
            if (!fields.add(field.name + ":" + field.desc)) {
                throw new IllegalArgumentException("Sanitized field collision: " + node.name + "." + field.name + field.desc);
            }
        });
        final Set<String> methods = new HashSet<>();
        node.methods.forEach(method -> {
            if (!methods.add(method.name + method.desc)) {
                throw new IllegalArgumentException("Sanitized method collision: " + node.name + "." + method.name + method.desc);
            }
        });
        for (var inner : node.innerClasses) {
            if (inner.innerName == null) {
                continue;
            }
            final int dollar = inner.name.lastIndexOf('$');
            final int slash = inner.name.lastIndexOf('/');
            if (dollar > slash) {
                inner.outerName = inner.name.substring(0, dollar);
                inner.innerName = inner.name.substring(dollar + 1);
            } else {
                inner.innerName = inner.name.substring(slash + 1);
            }
        }
        // 模组可能引用未启用的可选依赖；调用点改名及字符串替换保持栈类型，沿用 TR 已映射的帧。
        final ClassWriter writer = new ClassWriter(preserveFrames ? 0 : ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                return hierarchy.commonType(left, right, release);
            }
        };
        node.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                                             final String signature, final String[] exceptions) {
                return new JSRInlinerAdapter(super.visitMethod(access, name, descriptor, signature, exceptions),
                        access, name, descriptor, signature, exceptions);
            }
        });
        return writer.toByteArray();
    }
}
