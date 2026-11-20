package github.kasuminova.ssoptimizer.standalone.obf;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import static org.objectweb.asm.Opcodes.*;
import static org.junit.jupiter.api.Assertions.*;

class ObfClassesTest {
    private static final String OWNER = "sample/Game";
    private static final String RAW = "Object.super";

    @Test void comparesTheReceiverAndPreservesUnknownBranches() {
        var game = new ClassWriter(0);
        game.visit(V17, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        game.visitField(ACC_PUBLIC, RAW, "I", null, null).visitEnd();
        game.visitEnd();
        byte[] original = game.toByteArray();
        var members = new GameMembers();
        members.index(new ClassReader(original));
        String physical = ObfClasses.memberName(RAW, false);
        var field = parse(ObfClasses.normalize(original)).fields.getFirst();
        assertEquals(physical, field.name);
        assertEquals(RAW, field.invisibleAnnotations.getFirst().values.get(1));
        byte[] positive = caller(OWNER, false);
        byte[] normalized = ObfClasses.normalize(positive, "sample/Caller", members);
        var instructions = parse(normalized).methods.getFirst().instructions.toArray();
        assertTrue(Arrays.stream(instructions).anyMatch(i -> i instanceof LdcInsnNode literal && physical.equals(literal.cst)));
        assertSame(normalized, ObfClasses.normalize(normalized, "sample/Caller", members));
        byte[] unknownName = caller(OWNER, true);
        byte[] unknownOwner = caller("sample/Other", false);
        assertSame(unknownName, ObfClasses.normalize(unknownName, "sample/Caller", members));
        assertSame(unknownOwner, ObfClasses.normalize(unknownOwner, "sample/Caller", members));
    }

    @Test void removesInvalidOrphanPoolReferencesAndRepairsLocalNames() {
        var writer = new ClassWriter(0);
        writer.visit(V17, ACC_PUBLIC, "sample/Debug", null, "java/lang/Object", null);
        writer.newMethod(OWNER, RAW, "()V", false);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "run", "(I)V", null, null);
        Label begin = new Label(), end = new Label();
        method.visitCode();
        method.visitLabel(begin);
        method.visitInsn(RETURN);
        method.visitLabel(end);
        method.visitLocalVariable("", "I", null, begin, end, 0);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        byte[] fixed = ObfClasses.normalize(writer.toByteArray());
        assertEquals("local0", parse(fixed).methods.getFirst().localVariables.getFirst().name);
        assertSame(fixed, ObfClasses.normalize(fixed));
    }

    private static byte[] caller(String target, boolean merged) {
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC, "sample/Caller", null, "java/lang/Object", null);
        var method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "query", "(ZLjava/lang/String;)V", null, null);
        method.visitCode();
        method.visitLdcInsn(Type.getObjectType(target));
        if (merged) {
            var known = new Label();
            var joined = new Label();
            method.visitVarInsn(ILOAD, 0);
            method.visitJumpInsn(IFEQ, known);
            method.visitVarInsn(ALOAD, 1);
            method.visitJumpInsn(GOTO, joined);
            method.visitLabel(known);
            method.visitLdcInsn(RAW);
            method.visitLabel(joined);
        } else method.visitLdcInsn(RAW);
        method.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Class", "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;", false);
        method.visitInsn(POP);
        method.visitInsn(RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassNode parse(byte[] bytes) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }
}
