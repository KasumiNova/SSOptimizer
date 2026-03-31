package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class TextureObjectBindProcessorTest {
    @Test
    void rewritesBindMethodToLazyHelper() {
        byte[] rewritten = new TextureObjectBindProcessor().process(createFakeTextureObjectClass());
        assertNotNull(rewritten);

        boolean[] helperCall = {false};
        boolean[] rawBind = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!TextureObjectBindProcessor.TARGET_METHOD.equals(name)
                        || !TextureObjectBindProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (TextureObjectBindProcessor.HELPER_OWNER.equals(owner)
                                && TextureObjectBindProcessor.HELPER_METHOD.equals(methodName)
                                && TextureObjectBindProcessor.HELPER_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                        if ("org/lwjgl/opengl/GL11".equals(owner) && "glBindTexture".equals(methodName)) {
                            rawBind[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "Texture object bind should delegate to LazyTextureManager.bindTexture");
        assertFalse(rawBind[0], "Texture object bind should not keep direct GL11.glBindTexture calls");
    }

    @Test
    void rewritesTextureIdGetterToLazyHelper() {
        byte[] rewritten = new TextureObjectBindProcessor().process(createFakeTextureObjectClass());
        assertNotNull(rewritten);

        boolean[] helperCall = {false};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!TextureObjectBindProcessor.TARGET_ID_GETTER_METHOD.equals(name)
                        || !TextureObjectBindProcessor.TARGET_ID_GETTER_DESC.equals(desc)) {
                    return null;
                }

                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (TextureObjectBindProcessor.HELPER_OWNER.equals(owner)
                                && TextureObjectBindProcessor.HELPER_ID_METHOD.equals(methodName)
                                && TextureObjectBindProcessor.HELPER_ID_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "Texture object id getter should delegate to LazyTextureManager.getTextureId");
    }

    @Test
    void ignoresNonTargetClasses() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "com/example/Other", null, "java/lang/Object", null);
        cw.visitEnd();

        assertNull(new TextureObjectBindProcessor().process(cw.toByteArray()));
    }

    private byte[] createFakeTextureObjectClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, TextureObjectBindProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "ö00000", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "ô00000", "I", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 1);
        ctor.visitEnd();

        MethodVisitor bind = cw.visitMethod(Opcodes.ACC_PUBLIC, TextureObjectBindProcessor.TARGET_METHOD,
                TextureObjectBindProcessor.TARGET_DESC, null, null);
        bind.visitCode();
        bind.visitVarInsn(Opcodes.ALOAD, 0);
        bind.visitFieldInsn(Opcodes.GETFIELD, TextureObjectBindProcessor.TARGET_CLASS, "ö00000", "I");
        bind.visitInsn(Opcodes.ICONST_0);
        bind.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glBindTexture", "(II)V", false);
        bind.visitInsn(Opcodes.RETURN);
        bind.visitMaxs(0, 1);
        bind.visitEnd();

        MethodVisitor getter = cw.visitMethod(Opcodes.ACC_PUBLIC, TextureObjectBindProcessor.TARGET_ID_GETTER_METHOD,
                TextureObjectBindProcessor.TARGET_ID_GETTER_DESC, null, null);
        getter.visitCode();
        getter.visitVarInsn(Opcodes.ALOAD, 0);
        getter.visitFieldInsn(Opcodes.GETFIELD, TextureObjectBindProcessor.TARGET_CLASS, "ô00000", "I");
        getter.visitInsn(Opcodes.IRETURN);
        getter.visitMaxs(0, 1);
        getter.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}