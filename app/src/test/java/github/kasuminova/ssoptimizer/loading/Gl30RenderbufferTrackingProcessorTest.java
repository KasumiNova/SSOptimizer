package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class Gl30RenderbufferTrackingProcessorTest {
    @Test
    void injectsRenderbufferTrackingHooks() {
        byte[] rewritten = new Gl30RenderbufferTrackingProcessor().process(createFakeGl30Class());
        assertNotNull(rewritten);

        boolean[] storageHook = {false};
        boolean[] deleteOneHook = {false};
        boolean[] deleteManyHook = {false};

        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if ("glRenderbufferStorage".equals(name)
                                && Gl30RenderbufferTrackingProcessor.RENDERBUFFER_STORAGE_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "afterRenderbufferStorage".equals(methodName)) {
                            storageHook[0] = true;
                        }
                        if ("glDeleteRenderbuffers".equals(name)
                                && Gl30RenderbufferTrackingProcessor.DELETE_RENDERBUFFER_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "beforeDeleteRenderbuffer".equals(methodName)) {
                            deleteOneHook[0] = true;
                        }
                        if ("glDeleteRenderbuffers".equals(name)
                                && Gl30RenderbufferTrackingProcessor.DELETE_RENDERBUFFERS_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "beforeDeleteRenderbuffers".equals(methodName)) {
                            deleteManyHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(storageHook[0]);
        assertTrue(deleteOneHook[0]);
        assertTrue(deleteManyHook[0]);
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjgl/opengl/GL31", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new Gl30RenderbufferTrackingProcessor().process(cw.toByteArray()));
    }

    private byte[] createFakeGl30Class() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, Gl30RenderbufferTrackingProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor storage = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glRenderbufferStorage",
                Gl30RenderbufferTrackingProcessor.RENDERBUFFER_STORAGE_DESC, null, null);
        storage.visitCode();
        storage.visitInsn(Opcodes.RETURN);
        storage.visitMaxs(0, 4);
        storage.visitEnd();

        MethodVisitor deleteOne = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glDeleteRenderbuffers",
                Gl30RenderbufferTrackingProcessor.DELETE_RENDERBUFFER_DESC, null, null);
        deleteOne.visitCode();
        deleteOne.visitInsn(Opcodes.RETURN);
        deleteOne.visitMaxs(0, 1);
        deleteOne.visitEnd();

        MethodVisitor deleteMany = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glDeleteRenderbuffers",
                Gl30RenderbufferTrackingProcessor.DELETE_RENDERBUFFERS_DESC, null, null);
        deleteMany.visitCode();
        deleteMany.visitInsn(Opcodes.RETURN);
        deleteMany.visitMaxs(0, 1);
        deleteMany.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
