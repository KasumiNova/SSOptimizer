package github.kasuminova.ssoptimizer.asm.ime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WindowsDisplayImeProcessorTest {
    @Test
    void injectsAttachDetachAndFocusHooks() {
        final byte[] rewritten = new WindowsDisplayImeProcessor().process(createFakeWindowsDisplayClass());
        assertNotNull(rewritten);

        final List<String> createKeyboardCalls = new ArrayList<>();
        final List<String> destroyKeyboardCalls = new ArrayList<>();
        final List<String> appActivateCalls = new ArrayList<>();

        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String desc,
                                             final String signature,
                                             final String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode,
                                                final String owner,
                                                final String methodName,
                                                final String methodDesc,
                                                final boolean isInterface) {
                        final String call = owner + "#" + methodName + methodDesc;
                        if ("createKeyboard".equals(name)) {
                            createKeyboardCalls.add(call);
                        } else if ("destroyKeyboard".equals(name)) {
                            destroyKeyboardCalls.add(call);
                        } else if ("appActivate".equals(name)) {
                            appActivateCalls.add(call);
                        }
                    }
                };
            }
        }, 0);

        assertTrue(createKeyboardCalls.contains(WindowsDisplayImeProcessor.HOOK_OWNER + "#afterCreateKeyboard(Ljava/lang/Object;)V"));
        assertTrue(destroyKeyboardCalls.contains(WindowsDisplayImeProcessor.HOOK_OWNER + "#beforeDestroyKeyboard(Ljava/lang/Object;)V"));
        assertTrue(appActivateCalls.contains(WindowsDisplayImeProcessor.HOOK_OWNER + "#onFocusChanged(Ljava/lang/Object;Z)V"));
    }

    private byte[] createFakeWindowsDisplayClass() {
        final ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, WindowsDisplayImeProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        MethodVisitor createKeyboard = cw.visitMethod(Opcodes.ACC_PUBLIC, "createKeyboard", "()V", null, null);
        createKeyboard.visitCode();
        createKeyboard.visitInsn(Opcodes.RETURN);
        createKeyboard.visitMaxs(0, 1);
        createKeyboard.visitEnd();

        MethodVisitor destroyKeyboard = cw.visitMethod(Opcodes.ACC_PUBLIC, "destroyKeyboard", "()V", null, null);
        destroyKeyboard.visitCode();
        destroyKeyboard.visitInsn(Opcodes.RETURN);
        destroyKeyboard.visitMaxs(0, 1);
        destroyKeyboard.visitEnd();

        MethodVisitor appActivate = cw.visitMethod(Opcodes.ACC_PRIVATE, "appActivate", "(ZJ)V", null, null);
        appActivate.visitCode();
        appActivate.visitInsn(Opcodes.RETURN);
        appActivate.visitMaxs(0, 4);
        appActivate.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}