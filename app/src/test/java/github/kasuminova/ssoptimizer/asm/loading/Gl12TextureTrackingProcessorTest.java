package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class Gl12TextureTrackingProcessorTest {
    @Test
    void injects3dTextureTrackingHook() {
        byte[] rewritten = new Gl12TextureTrackingProcessor().process(createFakeGl12Class());
        assertNotNull(rewritten);

        boolean[] texImage3DHook = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if ("glTexImage3D".equals(name)
                                && Gl12TextureTrackingProcessor.TEX_IMAGE_3D_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/common/loading/RuntimeGlResourceTracker".equals(owner)
                                && "afterTexImage3D".equals(methodName)) {
                            texImage3DHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(texImage3DHook[0]);
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjgl/opengl/GL13", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new Gl12TextureTrackingProcessor().process(cw.toByteArray()));
    }

    private byte[] createFakeGl12Class() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, Gl12TextureTrackingProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor texImage = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glTexImage3D",
                Gl12TextureTrackingProcessor.TEX_IMAGE_3D_DESC, null, null);
        texImage.visitCode();
        texImage.visitInsn(Opcodes.RETURN);
        texImage.visitMaxs(0, 10);
        texImage.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
