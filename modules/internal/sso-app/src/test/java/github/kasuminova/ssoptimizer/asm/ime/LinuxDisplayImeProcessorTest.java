package github.kasuminova.ssoptimizer.asm.ime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxDisplayImeProcessorTest {
    private static final String HOOK = LinuxDisplayImeProcessor.HOOK_OWNER + "#";

    @Test
    void injectsImeHooksIntoLinuxDisplayLifecycle() {
        final byte[] rewritten = new LinuxDisplayImeProcessor().process(createFakeLinuxDisplayClass());
        assertNotNull(rewritten);

        final Map<String, List<String>> callsByMethod = new HashMap<>();
        final Set<String> methodNames = new HashSet<>();
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String sig, final String[] ex) {
                methodNames.add(name);
                final List<String> calls = callsByMethod.computeIfAbsent(name, key -> new ArrayList<>());
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                                final String methodDesc, final boolean itf) {
                        calls.add(owner + "#" + methodName + methodDesc);
                    }
                };
            }
        }, 0);

        assertTrue(callsByMethod.get("createKeyboard").contains(HOOK + "afterCreateKeyboard(JJ)V"));
        assertTrue(callsByMethod.get("destroyKeyboard").contains(HOOK + "beforeDestroyKeyboard(Ljava/lang/Object;)V"));
        assertTrue(callsByMethod.get("setFocused").contains(HOOK + "onFocusChanged(Ljava/lang/Object;Z)V"));

        final List<String> processEvents = callsByMethod.get("processEvents");
        final int nextEvent = processEvents.indexOf("org/lwjgl/opengl/LinuxEvent#nextEvent(J)V");
        final int rawHook = processEvents.indexOf(HOOK + "onRawXEvent(IJ)Z");
        final int filterEvent = processEvents.indexOf("org/lwjgl/opengl/LinuxEvent#filterEvent(J)Z");
        final int xHook = processEvents.indexOf(HOOK + "onXEvent(IJJJ)Z");
        assertTrue(nextEvent >= 0, "原 nextEvent 调用必须保留");
        assertTrue(nextEvent < rawHook, "onRawXEvent 必须位于 nextEvent 之后");
        assertTrue(rawHook < filterEvent, "onRawXEvent 必须位于 filterEvent 之前");
        assertTrue(filterEvent < xHook, "onXEvent 必须位于 filterEvent 之后");

        assertTrue(methodNames.contains(LinuxDisplayImeProcessor.CLEAR_EVENT_METHOD), "必须生成事件清空辅助方法");
        assertTrue(processEvents.contains(LinuxDisplayImeProcessor.TARGET_CLASS + "#"
                + LinuxDisplayImeProcessor.CLEAR_EVENT_METHOD + LinuxDisplayImeProcessor.CLEAR_EVENT_DESC));
        assertTrue(callsByMethod.get(LinuxDisplayImeProcessor.CLEAR_EVENT_METHOD)
                .contains("org/lwjgl/opengl/LinuxEvent#copyFrom(Lorg/lwjgl/opengl/LinuxEvent;)V"));
    }

    private byte[] createFakeLinuxDisplayClass() {
        final ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, LinuxDisplayImeProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;", null, null).visitEnd();

        for (final String staticGetter : new String[]{"getDisplay", "getWindow"}) {
            final MethodVisitor getter = cw.visitMethod(Opcodes.ACC_STATIC, staticGetter, "()J", null, null);
            getter.visitCode();
            getter.visitInsn(Opcodes.LCONST_0);
            getter.visitInsn(Opcodes.LRETURN);
            getter.visitMaxs(0, 0);
            getter.visitEnd();
        }

        final MethodVisitor createKeyboard = cw.visitMethod(Opcodes.ACC_PUBLIC, "createKeyboard", "()V", null, null);
        createKeyboard.visitCode();
        createKeyboard.visitInsn(Opcodes.RETURN);
        createKeyboard.visitMaxs(0, 1);
        createKeyboard.visitEnd();

        final MethodVisitor destroyKeyboard = cw.visitMethod(Opcodes.ACC_PUBLIC, "destroyKeyboard", "()V", null, null);
        destroyKeyboard.visitCode();
        destroyKeyboard.visitInsn(Opcodes.RETURN);
        destroyKeyboard.visitMaxs(0, 1);
        destroyKeyboard.visitEnd();

        final MethodVisitor setFocused = cw.visitMethod(Opcodes.ACC_PRIVATE, "setFocused", "(ZI)V", null, null);
        setFocused.visitCode();
        setFocused.visitInsn(Opcodes.RETURN);
        setFocused.visitMaxs(0, 3);
        setFocused.visitEnd();

        final MethodVisitor processEvents = cw.visitMethod(Opcodes.ACC_PRIVATE, "processEvents", "()V", null, null);
        processEvents.visitCode();
        processEvents.visitVarInsn(Opcodes.ALOAD, 0);
        processEvents.visitFieldInsn(Opcodes.GETFIELD, LinuxDisplayImeProcessor.TARGET_CLASS, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;");
        processEvents.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxDisplayImeProcessor.TARGET_CLASS, "getDisplay", "()J", false);
        processEvents.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/opengl/LinuxEvent", "nextEvent", "(J)V", false);
        processEvents.visitVarInsn(Opcodes.ALOAD, 0);
        processEvents.visitFieldInsn(Opcodes.GETFIELD, LinuxDisplayImeProcessor.TARGET_CLASS, "event_buffer", "Lorg/lwjgl/opengl/LinuxEvent;");
        processEvents.visitInsn(Opcodes.LCONST_0);
        processEvents.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "org/lwjgl/opengl/LinuxEvent", "filterEvent", "(J)Z", false);
        final Label skip = new Label();
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
