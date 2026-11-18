package github.kasuminova.ssoptimizer.standalone.remap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

class ModClassStructureTest {
    @Test
    void rejectsConflictingAccessAndInvalidDescriptors() {
        assertThrows(IllegalArgumentException.class, () -> check(ACC_PUBLIC | ACC_FINAL | ACC_ABSTRACT, w -> {}));
        assertThrows(IllegalArgumentException.class, () -> check(ACC_PUBLIC,
                w -> w.visitField(ACC_PUBLIC | ACC_PRIVATE, "field", "I", null, null).visitEnd()));
        assertThrows(IllegalArgumentException.class, () -> check(ACC_PUBLIC,
                w -> w.visitField(ACC_PUBLIC, "field", "V", null, null).visitEnd()));
        assertThrows(IllegalArgumentException.class, () -> check(ACC_ABSTRACT,
                w -> w.visitMethod(ACC_ABSTRACT, "test", "(Q)V", null, null).visitEnd()));
        assertThrows(IllegalArgumentException.class, () -> check(ACC_PUBLIC,
                w -> w.visitMethod(ACC_PUBLIC, "test", "()V", null, null).visitEnd()));
    }

    @Test
    void rejectsMethodReferenceOfWrongConstantPoolKind() {
        assertThrows(IllegalArgumentException.class, () -> check(ACC_PUBLIC, w -> {
            var m = w.visitMethod(ACC_STATIC, "use", "(Lmissing/Type;)V", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitMethodInsn(INVOKEVIRTUAL, "missing/Type", "call", "()V", true);
            m.visitInsn(RETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }));
    }

    @Test
    void leavesRepairableNamesAndMissingOwnersToRemapping() {
        assertDoesNotThrow(() -> check(ACC_ABSTRACT, w -> {
            w.visitField(ACC_PRIVATE, "before.after", "Loptional/Missing;", null, null).visitEnd();
            var m = w.visitMethod(ACC_STATIC, "do.new", "(Loptional/Missing;J)V", null, null);
            m.visitCode();
            m.visitVarInsn(ALOAD, 0);
            m.visitVarInsn(LLOAD, 1);
            m.visitMethodInsn(INVOKEINTERFACE, "optional/Missing", "check", "(J)V", true);
            m.visitInsn(RETURN);
            m.visitMaxs(3, 3);
            m.visitEnd();
        }));
    }

    private static void check(int flags, Consumer<ClassWriter> members) {
        var w = new ClassWriter(0);
        w.visit(V17, flags, "example/Type", null, "java/lang/Object", null);
        members.accept(w);
        w.visitEnd();
        byte[] bytes = w.toByteArray();
        ModClassStructure.check(bytes, ArchivePreparationTest.node(bytes));
    }
}
