package github.kasuminova.ssoptimizer.asm.ime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxDisplayImeProcessorTest {
    @Test
    void injectsImeHooksIntoLinuxDisplayLifecycle() {
        byte[] rewritten = new LinuxDisplayImeProcessor().process(createFakeLinuxDisplayClass());
        assertNotNull(rewritten);

        boolean[] processEventsHook = {false};
        boolean[] rawXEventHook = {false};
        boolean[] createKeyboardHook = {false};
        boolean[] destroyKeyboardHook = {false};
        boolean[] focusHook = {false};
        List<String> processEventsCalls = new ArrayList<>();

        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if ("processEvents".equals(name)) {
                            processEventsCalls.add(owner + "#" + methodName + methodDesc);
                        }
                        if (owner.equals(LinuxDisplayImeProcessor.HOOK_OWNER) && methodName.equals("afterCreateKeyboard")) {
                            createKeyboardHook[0] = true;
                        }
                        if (owner.equals(LinuxDisplayImeProcessor.HOOK_OWNER) && methodName.equals("beforeDestroyKeyboard")) {
                            destroyKeyboardHook[0] = true;
                        }
                        if (owner.equals(LinuxDisplayImeProcessor.HOOK_OWNER) && methodName.equals("onFocusChanged")) {
                            focusHook[0] = true;
                        }
                        if (owner.equals(LinuxDisplayImeProcessor.HOOK_OWNER) && methodName.equals("onXEvent")) {
                            processEventsHook[0] = true;
                        }
                        if (owner.equals(LinuxDisplayImeProcessor.HOOK_OWNER) && methodName.equals("onRawXEvent")) {
                            rawXEventHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(createKeyboardHook[0]);
        assertTrue(destroyKeyboardHook[0]);
        assertTrue(focusHook[0]);
        assertTrue(processEventsHook[0]);
        assertTrue(rawXEventHook[0], "onRawXEvent hook should be injected after nextEvent()");
        // onRawXEvent must appear after nextEvent and before filterEvent
        int nextEventIdx = processEventsCalls.indexOf("org/lwjgl/opengl/LinuxEvent#nextEvent(J)V");
        String rawXEventCall = LinuxDisplayImeProcessor.HOOK_OWNER + "#onRawXEvent(Ljava/lang/Object;)V";
        int rawXEventIdx = processEventsCalls.indexOf(rawXEventCall);
        int filterEventIdx = processEventsCalls.indexOf("org/lwjgl/opengl/LinuxEvent#filterEvent(J)Z");
        assertTrue(nextEventIdx < rawXEventIdx, "onRawXEvent must come after nextEvent");
        assertTrue(rawXEventIdx < filterEventIdx, "onRawXEvent must come before filterEvent");
        assertTrue(processEventsCalls.indexOf("org/lwjgl/opengl/LinuxEvent#filterEvent(J)Z")
                < processEventsCalls.indexOf(LinuxDisplayImeProcessor.HOOK_OWNER + "#onXEvent(Ljava/lang/Object;Ljava/lang/Object;Z)V"));
    }

    private byte[] createFakeLinuxDisplayClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, LinuxDisplayImeProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;", null, null).visitEnd();

        MethodVisitor getDisplay = cw.visitMethod(Opcodes.ACC_STATIC, "getDisplay", "()J", null, null);
        getDisplay.visitCode();
        getDisplay.visitInsn(Opcodes.LCONST_0);
        getDisplay.visitInsn(Opcodes.LRETURN);
        getDisplay.visitMaxs(0, 0);
        getDisplay.visitEnd();

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

        MethodVisitor setFocused = cw.visitMethod(Opcodes.ACC_PRIVATE, "setFocused", "(ZI)V", null, null);
        setFocused.visitCode();
        setFocused.visitInsn(Opcodes.RETURN);
        setFocused.visitMaxs(0, 3);
        setFocused.visitEnd();

        MethodVisitor processEvents = cw.visitMethod(Opcodes.ACC_PRIVATE, "processEvents", "()V", null, null);
        processEvents.visitCode();
        processEvents.visitVarInsn(Opcodes.ALOAD, 0);
        processEvents.visitFieldInsn(Opcodes.GETFIELD, LinuxDisplayImeProcessor.TARGET_CLASS, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;");
        processEvents.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxDisplayImeProcessor.TARGET_CLASS, "getDisplay", "()J", false);
        processEvents.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/opengl/LinuxEvent", "nextEvent", "(J)V", false);
        processEvents.visitVarInsn(Opcodes.ALOAD, 0);
        processEvents.visitFieldInsn(Opcodes.GETFIELD, LinuxDisplayImeProcessor.TARGET_CLASS, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;");
        processEvents.visitInsn(Opcodes.LCONST_0);
        processEvents.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/opengl/LinuxEvent", "filterEvent", "(J)Z", false);
        Label skip = new Label();
        processEvents.visitJumpInsn(Opcodes.IFNE, skip);
        processEvents.visitInsn(Opcodes.RETURN);
        processEvents.visitLabel(skip);
        processEvents.visitInsn(Opcodes.RETURN);
        processEvents.visitMaxs(0, 1);
        processEvents.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
