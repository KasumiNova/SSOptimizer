package github.kasuminova.ssoptimizer.asm.ime;

import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMixinSignatures;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFieldFactoryProcessorTest {
    @Test
    void rewritesFactoryMethodToRegisterCreatedTextField() {
        byte[] rewritten = new TooltipTextFieldFactoryProcessor(
            GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE
        ).process(createFakeFactoryClass(GameClassNames.STANDARD_TOOLTIP_V2_EXPANDABLE));
        assertNotNull(rewritten);

        boolean[] foundRegisterHook = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(TooltipTextFieldFactoryProcessor.HOOK_OWNER)
                                && methodName.equals("registerCreatedTextField")) {
                            foundRegisterHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundRegisterHook[0]);
    }

    @Test
    void rewritesTextFieldConstructorToRegisterCreatedTextField() {
        byte[] rewritten = new TextFieldImplementationProcessor(
            GameClassNames.TEXT_FIELD_IMPL
        ).process(createFakeTextFieldImplementationClass(GameClassNames.TEXT_FIELD_IMPL));
        assertNotNull(rewritten);

        boolean[] foundRegisterHook = {false};
        boolean[] foundFocusGainHook = {false};
        boolean[] foundFocusLostHook = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.equals(TooltipTextFieldFactoryProcessor.HOOK_OWNER)) {
                            if (methodName.equals("registerCreatedTextField")) {
                                foundRegisterHook[0] = true;
                            }
                            if (methodName.equals("onTextFieldFocusGained")) {
                                foundFocusGainHook[0] = true;
                            }
                            if (methodName.equals("onTextFieldFocusLost")) {
                                foundFocusLostHook[0] = true;
                            }
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundRegisterHook[0]);
        assertTrue(foundFocusGainHook[0]);
        assertTrue(foundFocusLostHook[0]);
    }

    private byte[] createFakeFactoryClass(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, GameMixinSignatures.TextFieldIme.ADD_TEXT_FIELD,
            "(FF)" + GameMixinSignatures.TextFieldIme.TEXT_FIELD_API_DESC, null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private byte[] createFakeTextFieldImplementationClass(String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object",
            new String[]{GameClassNames.TEXT_FIELD_API});

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 1);
        ctor.visitEnd();

        MethodVisitor grabFocus = cw.visitMethod(Opcodes.ACC_PUBLIC,
            GameMixinSignatures.TextFieldIme.GRAB_FOCUS,
            GameMixinSignatures.TextFieldIme.GRAB_FOCUS_DESC,
            null,
            null);
        grabFocus.visitCode();
        grabFocus.visitInsn(Opcodes.RETURN);
        grabFocus.visitMaxs(0, 2);
        grabFocus.visitEnd();

        MethodVisitor releaseFocus = cw.visitMethod(Opcodes.ACC_PUBLIC,
            GameMixinSignatures.TextFieldIme.RELEASE_FOCUS,
            GameMixinSignatures.TextFieldIme.RELEASE_FOCUS_DESC,
            null,
            null);
        releaseFocus.visitCode();
        releaseFocus.visitInsn(Opcodes.RETURN);
        releaseFocus.visitMaxs(0, 2);
        releaseFocus.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
