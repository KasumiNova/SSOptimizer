package github.kasuminova.ssoptimizer.input.ime;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinuxEventImeProcessorTest {

    @Test
    void filterEventAlwaysReturnsFalse() throws Exception {
        byte[] rewritten = new LinuxEventImeProcessor().process(createFakeLinuxEventClass());
        assertNotNull(rewritten, "processor should have modified the class");

        // Verify nFilterEvent is NOT called from filterEvent
        List<String> filterCalls = new ArrayList<>();
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if ("filterEvent".equals(name) && "(J)Z".equals(desc)) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDesc, boolean itf) {
                            filterCalls.add(owner + "#" + methodName + methodDesc);
                        }
                    };
                }
                return null;
            }
        }, 0);

        assertTrue(filterCalls.isEmpty(),
                "filterEvent should not call any methods (nFilterEvent should be removed), but found: " + filterCalls);

        // Load and instantiate the rewritten class to verify it returns false
        ClassLoader loader = new ClassLoader(getClass().getClassLoader()) {
            {
                defineClass(LinuxEventImeProcessor.TARGET_CLASS.replace('/', '.'),
                        rewritten, 0, rewritten.length);
            }
        };
        Class<?> clazz = loader.loadClass(LinuxEventImeProcessor.TARGET_CLASS.replace('/', '.'));
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Method filterEvent = clazz.getMethod("filterEvent", long.class);
        boolean result = (boolean) filterEvent.invoke(instance, 42L);
        assertEquals(false, result, "filterEvent should always return false");
    }

    @Test
    void returnsNullForNonMatchingClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();
        assertNull(new LinuxEventImeProcessor().process(cw.toByteArray()),
                "processor should return null for non-matching class");
    }

    private byte[] createFakeLinuxEventClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                LinuxEventImeProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        // Field: event_buffer (ByteBuffer)
        cw.visitField(Opcodes.ACC_PRIVATE, "event_buffer", "Ljava/nio/ByteBuffer;", null, null).visitEnd();

        // Constructor
        MethodVisitor init = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        // filterEvent(J)Z — original calls nFilterEvent(event_buffer, display)
        MethodVisitor fe = cw.visitMethod(Opcodes.ACC_PUBLIC, "filterEvent", "(J)Z", null, null);
        fe.visitCode();
        fe.visitVarInsn(Opcodes.ALOAD, 0);
        fe.visitFieldInsn(Opcodes.GETFIELD, LinuxEventImeProcessor.TARGET_CLASS,
                "event_buffer", "Ljava/nio/ByteBuffer;");
        fe.visitVarInsn(Opcodes.LLOAD, 1);
        fe.visitMethodInsn(Opcodes.INVOKESTATIC, LinuxEventImeProcessor.TARGET_CLASS,
                "nFilterEvent", "(Ljava/nio/ByteBuffer;J)Z", false);
        fe.visitInsn(Opcodes.IRETURN);
        fe.visitMaxs(0, 0);
        fe.visitEnd();

        // nFilterEvent stub
        cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE,
                "nFilterEvent", "(Ljava/nio/ByteBuffer;J)Z", null, null).visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
