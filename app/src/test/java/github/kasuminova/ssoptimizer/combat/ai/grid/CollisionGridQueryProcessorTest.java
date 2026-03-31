package github.kasuminova.ssoptimizer.combat.ai.grid;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class CollisionGridQueryProcessorTest {
    @Test
    void getCheckIteratorCallsCollisionGridHelper() {
        byte[] rewritten = new CollisionGridQueryProcessor().process(createFakeCollisionGridClass());
        assertNotNull(rewritten);

        MethodInspection inspection = inspectMethod(rewritten);
        assertTrue(inspection.callsHelper, "getCheckIterator should call CollisionGridQueryHelper.getCheckIterator");
        assertFalse(inspection.callsDummyCall, "getCheckIterator should no longer contain original dummy bytecode");
    }

    private byte[] createFakeCollisionGridClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                CollisionGridQueryProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE, "class", "[[Ljava/util/List;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "\u00D300000", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "float", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "o00000", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "\u00F500000", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "new", "F", null, null).visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC,
                CollisionGridQueryProcessor.TARGET_METHOD,
                CollisionGridQueryProcessor.TARGET_DESC,
                null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                "java/util/Collections", "emptyIterator", "()Ljava/util/Iterator;", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 4);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private MethodInspection inspectMethod(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        boolean[] helper = {false};
        boolean[] dummyCall = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!CollisionGridQueryProcessor.TARGET_METHOD.equals(name)
                        || !CollisionGridQueryProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (CollisionGridQueryProcessor.HELPER_OWNER.equals(owner)
                                && CollisionGridQueryProcessor.TARGET_METHOD.equals(methodName)
                                && CollisionGridQueryProcessor.HELPER_DESC.equals(methodDesc)) {
                            helper[0] = true;
                        }
                        if ("java/util/Collections".equals(owner) && "emptyIterator".equals(methodName)) {
                            dummyCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        return new MethodInspection(helper[0], dummyCall[0]);
    }

    private record MethodInspection(boolean callsHelper, boolean callsDummyCall) {
    }
}