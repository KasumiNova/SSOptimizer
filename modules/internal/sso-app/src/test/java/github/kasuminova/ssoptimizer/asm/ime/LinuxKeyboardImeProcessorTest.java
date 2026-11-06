package github.kasuminova.ssoptimizer.asm.ime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LinuxKeyboardImeProcessorTest {

    @Test
    void removesOpenIMAndCreateICFromConstructor() {
        byte[] rewritten = new LinuxKeyboardImeProcessor().process(createFakeLinuxKeyboardClass());
        assertNotNull(rewritten, "processor should have modified the class");

        List<String> initCalls = new ArrayList<>();
        List<String> destroyCalls = new ArrayList<>();

        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        String call = owner + "#" + methodName + methodDesc;
                        if ("<init>".equals(name)) {
                            initCalls.add(call);
                        }
                        if ("destroy".equals(name)) {
                            destroyCalls.add(call);
                        }
                    }
                };
            }
        }, 0);

        for (String call : initCalls) {
            assertFalse(call.contains("openIM"), "openIM should have been removed from <init>");
            assertFalse(call.contains("createIC"), "createIC should have been removed from <init>");
            assertFalse(call.contains("setupIMEventMask"), "setupIMEventMask should have been removed from <init>");
        }

        for (String call : destroyCalls) {
            assertFalse(call.contains("destroyIC"), "destroyIC should have been removed from destroy");
            assertFalse(call.contains("closeIM"), "closeIM should have been removed from destroy");
        }

        assertTrue(initCalls.stream().anyMatch(c -> c.contains("<init>")),
                "super() call should still exist in <init>");
    }

    @Test
    void returnsNullForNonMatchingClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();
        byte[] result = new LinuxKeyboardImeProcessor().process(cw.toByteArray());
        assertNull(result, "processor should return null for non-matching class");
    }

    private byte[] createFakeLinuxKeyboardClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                LinuxKeyboardImeProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, "xim", "J", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "xic", "J", null, null).visitEnd();

        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(JJ)V", null, null);
        init.visitCode();

        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.LLOAD, 1);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxKeyboardImeProcessor.TARGET_CLASS,
                "openIM", "(J)J", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xim", "J");

        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xim", "J");
        init.visitVarInsn(Opcodes.LLOAD, 3);
        init.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxKeyboardImeProcessor.TARGET_CLASS,
                "createIC", "(JJ)J", false);
        init.visitFieldInsn(Opcodes.PUTFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xic", "J");

        init.visitVarInsn(Opcodes.LLOAD, 1);
        init.visitVarInsn(Opcodes.LLOAD, 3);
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitFieldInsn(Opcodes.GETFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xic", "J");
        init.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxKeyboardImeProcessor.TARGET_CLASS,
                "setupIMEventMask", "(JJJ)V", false);

        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor destroy = cw.visitMethod(Opcodes.ACC_PUBLIC, "destroy", "(J)V", null, null);
        destroy.visitCode();

        destroy.visitVarInsn(Opcodes.ALOAD, 0);
        destroy.visitFieldInsn(Opcodes.GETFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xic", "J");
        destroy.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxKeyboardImeProcessor.TARGET_CLASS,
                "destroyIC", "(J)V", false);

        destroy.visitVarInsn(Opcodes.ALOAD, 0);
        destroy.visitFieldInsn(Opcodes.GETFIELD, LinuxKeyboardImeProcessor.TARGET_CLASS, "xim", "J");
        destroy.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxKeyboardImeProcessor.TARGET_CLASS,
                "closeIM", "(J)V", false);

        destroy.visitInsn(Opcodes.RETURN);
        destroy.visitMaxs(0, 0);
        destroy.visitEnd();

        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "openIM", "(J)J", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "createIC", "(JJ)J", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "setupIMEventMask", "(JJJ)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "destroyIC", "(J)V", null, null).visitEnd();
        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "closeIM", "(J)V", null, null).visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
