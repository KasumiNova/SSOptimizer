package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LdcInsnNode;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BytecodeRepairTest {
    @Test
    void invalidDeclarationsAndOrphanConstantPoolReferencesAreRemovedWithoutChangingStrings() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Record", null, "java/lang/Object", null);
        writer.newMethod("example/Unused", "orphan.invalid", "()V", false);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bad.name", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn("bad.name");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        final byte[] original = writer.toByteArray();
        final byte[] repaired = BytecodeRepair.rewrite(original, new ClassHierarchy(), 0);
        final var node = ArchivePreparationTest.node(repaired);
        assertEquals(BytecodeRepair.memberName("bad.name", true), node.methods.getFirst().name);
        assertEquals("bad.name", ((LdcInsnNode) node.methods.getFirst().instructions.getFirst()).cst);
        final ClassReader reader = new ClassReader(repaired);
        final char[] chars = new char[reader.getMaxStringLength()];
        for (int i = 1; i < reader.getItemCount(); i++) {
            final int offset = reader.getItem(i);
            if (offset != 0 && reader.readByte(offset - 1) == 12) {
                final String name = reader.readUTF8(offset, chars);
                assertEquals(name, BytecodeRepair.memberName(name, true));
            }
        }
        class Definer extends ClassLoader {
            Class<?> define(final byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
        }
        assertThrows(ClassFormatError.class, () -> new Definer().define(original));
        assertDoesNotThrow(() -> new Definer().define(repaired));
    }

    @Test
    void framesMergeUsingActualJdkHierarchy() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Merge", null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose",
                "(ZLjava/net/URLClassLoader;Ljava/security/SecureClassLoader;)Ljava/lang/ClassLoader;", null, null);
        final Label other = new Label();
        final Label end = new Label();
        method.visitCode();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, other);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(other);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 3);
        method.visitEnd();
        writer.visitEnd();
        final var result = ArchivePreparationTest.node(BytecodeRepair.rewrite(writer.toByteArray(), new ClassHierarchy(), 0));
        assertTrue(Arrays.stream(result.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof FrameNode).map(instruction -> (FrameNode) instruction)
                .anyMatch(frame -> frame.stack != null && frame.stack.equals(java.util.List.of("java/security/SecureClassLoader"))));
    }

    @Test
    void nestedMetadataAndArrayCovarianceAreRebuilt() {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "named/Outer$Inner", null, "java/lang/Object", null);
        writer.visitInnerClass("named/Outer$Inner", "old/Outer", "old", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC);
        writer.visitInnerClass("named/Flat", "named/Outer", "old-flat", Opcodes.ACC_PUBLIC);
        writer.visitEnd();
        final var node = ArchivePreparationTest.node(BytecodeRepair.rewrite(writer.toByteArray(), new ClassHierarchy(), 0));
        assertEquals("named/Outer", node.innerClasses.getFirst().outerName);
        assertEquals("Inner", node.innerClasses.getFirst().innerName);
        assertEquals("Flat", node.innerClasses.get(1).innerName);
        final ClassHierarchy hierarchy = new ClassHierarchy();
        assertEquals("[Ljava/lang/Object;", hierarchy.commonType("[[I", "[[Ljava/lang/String;", 0));
        assertEquals("java/lang/Object", hierarchy.commonType("[I", "[J", 0));
        assertTrue(hierarchy.isAssignable("java/lang/Cloneable", "[I", 0));
        assertThrows(IllegalArgumentException.class, () -> hierarchy.commonType("missing/One", "missing/Two", 0));
    }
}
