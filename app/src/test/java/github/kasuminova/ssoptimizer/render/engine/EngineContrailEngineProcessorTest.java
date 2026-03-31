package github.kasuminova.ssoptimizer.render.engine;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class EngineContrailEngineProcessorTest {
    private byte[] createFakeContrailEngineClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, EngineContrailEngineProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        FieldVisitor field = cw.visitField(Opcodes.ACC_PRIVATE, "Ò00000", "Ljava/util/Map;", null, null);
        field.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "new", "(F)V", null, null);
        mv.visitCode();
        mv.visitIntInsn(Opcodes.SIPUSH, 3553);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glEnable", "(I)V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBegin", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    @Test
    void rewritesContrailRenderMethodToHelper() {
        byte[] rewritten = new EngineContrailEngineProcessor().process(createFakeContrailEngineClass());
        assertNotNull(rewritten);

        ClassReader reader = new ClassReader(rewritten);
        final boolean[] foundHelper = {false};
        final boolean[] foundDirectBegin = {false};

        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!"new".equals(name) || !"(F)V".equals(desc)) {
                    return null;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if (owner.equals(EngineContrailEngineProcessor.HELPER_OWNER)
                                && "renderContrails".equals(methodName)
                                && "(Ljava/lang/Object;F)V".equals(methodDesc)) {
                            foundHelper[0] = true;
                        }
                        if (owner.equals("org/lwjgl/opengl/GL11") && "glBegin".equals(methodName)) {
                            foundDirectBegin[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(foundHelper[0], "ContrailEngine.new(float) should delegate strip emission to ContrailBatchHelper");
        assertFalse(foundDirectBegin[0], "ContrailEngine.new(float) should not keep direct immediate-mode glBegin calls");
    }
}