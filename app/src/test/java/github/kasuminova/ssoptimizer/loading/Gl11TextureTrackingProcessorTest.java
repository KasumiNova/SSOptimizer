package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class Gl11TextureTrackingProcessorTest {
    @Test
    void injectsTextureUploadAndDeleteTrackingHooks() {
        byte[] rewritten = new Gl11TextureTrackingProcessor().process(createFakeGl11Class());
        assertNotNull(rewritten);

        boolean[] texImageHook = {false};
        boolean[] deleteTextureHook = {false};
        boolean[] deleteTexturesHook = {false};

        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if ("glTexImage2D".equals(name)
                                && Gl11TextureTrackingProcessor.TEX_IMAGE_2D_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "afterTexImage2D".equals(methodName)) {
                            texImageHook[0] = true;
                        }
                        if ("glDeleteTextures".equals(name)
                                && Gl11TextureTrackingProcessor.DELETE_TEXTURE_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "beforeDeleteTexture".equals(methodName)) {
                            deleteTextureHook[0] = true;
                        }
                        if ("glDeleteTextures".equals(name)
                                && Gl11TextureTrackingProcessor.DELETE_TEXTURES_DESC.equals(desc)
                                && "github/kasuminova/ssoptimizer/loading/RuntimeGlResourceTracker".equals(owner)
                                && "beforeDeleteTextures".equals(methodName)) {
                            deleteTexturesHook[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(texImageHook[0]);
        assertTrue(deleteTextureHook[0]);
        assertTrue(deleteTexturesHook[0]);
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "org/lwjgl/opengl/GL13", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new Gl11TextureTrackingProcessor().process(cw.toByteArray()));
    }

    private byte[] createFakeGl11Class() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, Gl11TextureTrackingProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor texImage = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glTexImage2D",
                Gl11TextureTrackingProcessor.TEX_IMAGE_2D_DESC, null, null);
        texImage.visitCode();
        texImage.visitInsn(Opcodes.RETURN);
        texImage.visitMaxs(0, 9);
        texImage.visitEnd();

        MethodVisitor deleteOne = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glDeleteTextures",
                Gl11TextureTrackingProcessor.DELETE_TEXTURE_DESC, null, null);
        deleteOne.visitCode();
        deleteOne.visitInsn(Opcodes.RETURN);
        deleteOne.visitMaxs(0, 1);
        deleteOne.visitEnd();

        MethodVisitor deleteMany = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "glDeleteTextures",
                Gl11TextureTrackingProcessor.DELETE_TEXTURES_DESC, null, null);
        deleteMany.visitCode();
        deleteMany.visitInsn(Opcodes.RETURN);
        deleteMany.visitMaxs(0, 1);
        deleteMany.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
