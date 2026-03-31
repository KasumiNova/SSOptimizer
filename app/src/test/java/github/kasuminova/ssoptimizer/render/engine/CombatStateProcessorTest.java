package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class CombatStateProcessorTest {
    private byte[] createFakeCombatState() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/fs/starfarer/combat/CombatState", null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "traverse", "()Ljava/lang/String;", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glFinish", "()V", false);
        mv.visitLdcInsn("ok");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void redirectsFinishCall() {
        byte[] rewritten = new CombatStateProcessor().process(createFakeCombatState());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundFinishHook = {false};
        final boolean[] foundDirectFinish = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (owner.contains("CombatStateTraversalHook") && methodName.equals("callFinishIfEnabled")) {
                            foundFinishHook[0] = true;
                        }
                        if (owner.equals("org/lwjgl/opengl/GL11") && methodName.equals("glFinish")) {
                            foundDirectFinish[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundFinishHook[0]);
        assertFalse(foundDirectFinish[0]);
    }
}