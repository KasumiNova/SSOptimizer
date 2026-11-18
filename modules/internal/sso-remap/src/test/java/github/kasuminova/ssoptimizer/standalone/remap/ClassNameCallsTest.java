package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.LdcInsnNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClassNameCallsTest {
    @TempDir Path temp;

    @Test
    void exactLookupArgumentsChangeWhileIdenticalUiStringsRemainUnchanged() throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Lookup", null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Ljava/lang/ClassLoader;)V", null, null);
        method.visitCode();
        method.visitLdcInsn("a.A");
        final Label debug = new Label();
        method.visitLabel(debug);
        method.visitLineNumber(12, debug);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("a.A");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Ui", "show", "(Ljava/lang/String;)V", false);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn("a.A");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("[La.A;");
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 1);
        method.visitEnd();
        writer.visitEnd();
        final var result = ArchivePreparationTest.node(BytecodeRepair.rewrite(writer.toByteArray(), new ClassHierarchy(), 0, mapping()));
        final List<Object> constants = Arrays.stream(result.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof LdcInsnNode).map(instruction -> ((LdcInsnNode) instruction).cst).toList();
        assertEquals(List.of("named.Target", "a.A", "named.Target", "[Lnamed.Target;"), constants);
    }

    @Test
    void aLiteralSharedAcrossUiAndLookupControlFlowIsNotChanged() throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Shared", null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(Z)V", null, null);
        final Label lookup = new Label();
        final Label end = new Label();
        method.visitCode();
        method.visitLdcInsn("a.A");
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, lookup);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Ui", "show", "(Ljava/lang/String;)V", false);
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(lookup);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLabel(end);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        final var result = ArchivePreparationTest.node(BytecodeRepair.rewrite(writer.toByteArray(), new ClassHierarchy(), 0, mapping()));
        final List<Object> constants = Arrays.stream(result.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof LdcInsnNode).map(instruction -> ((LdcInsnNode) instruction).cst).toList();
        assertEquals(List.of("a.A"), constants);
    }

    @Test
    void duplicatedUiLiteralAndNamedLookupCandidateRemainUnchanged() throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "mod/Duplicated", null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("a.A");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Ui", "show", "(Ljava/lang/String;)V", false);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitLdcInsn("named.Target");
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(2, 0);
        method.visitEnd();
        writer.visitEnd();
        final var result = ArchivePreparationTest.node(BytecodeRepair.rewrite(writer.toByteArray(), new ClassHierarchy(), 0, mapping()));
        final List<Object> constants = Arrays.stream(result.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof LdcInsnNode).map(instruction -> ((LdcInsnNode) instruction).cst).toList();
        assertEquals(List.of("a.A", "named.Target"), constants);
    }

    @Test
    void legacyRetCannotShareAChangedLiteralWithUiCode() throws Exception {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC, "mod/Legacy", null, "java/lang/Object", null);
        final var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        final Label start = new Label();
        final Label subroutine = new Label();
        final Label lookup = new Label();
        method.visitCode();
        method.visitJumpInsn(Opcodes.GOTO, start);
        method.visitLabel(subroutine);
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitLdcInsn("a.A");
        method.visitVarInsn(Opcodes.RET, 0);
        method.visitLabel(lookup);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName", "(Ljava/lang/String;)Ljava/lang/Class;", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(start);
        method.visitJumpInsn(Opcodes.JSR, subroutine);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "mod/Ui", "show", "(Ljava/lang/String;)V", false);
        method.visitJumpInsn(Opcodes.JSR, subroutine);
        method.visitJumpInsn(Opcodes.GOTO, lookup);
        method.visitMaxs(2, 1);
        method.visitEnd();
        writer.visitEnd();
        final var node = ArchivePreparationTest.node(writer.toByteArray());
        ClassNameCalls.rewrite(node, mapping());
        final List<Object> constants = Arrays.stream(node.methods.getFirst().instructions.toArray())
                .filter(instruction -> instruction instanceof LdcInsnNode).map(instruction -> ((LdcInsnNode) instruction).cst).toList();
        assertEquals(List.of("a.A"), constants);
    }

    private MappingProjection mapping() throws Exception {
        final Path path = temp.resolve("mapping.tiny");
        Files.writeString(path, "tiny\t2\t0\tobf\tnamed\nc\ta/A\tnamed/Target\n");
        return MappingProjection.read(path);
    }
}
