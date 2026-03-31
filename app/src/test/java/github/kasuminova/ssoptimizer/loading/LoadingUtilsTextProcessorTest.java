package github.kasuminova.ssoptimizer.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class LoadingUtilsTextProcessorTest {
    @Test
    void rewritesStaticInputStreamStringMethodToFastHelper() {
        byte[] rewritten = new LoadingUtilsTextProcessor().process(createFakeLoadingUtilsClass());
        assertNotNull(rewritten);

        boolean[] helperCall = {false};
        int[] aloadVar = {-1};
        new ClassReader(rewritten).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!LoadingUtilsTextProcessor.TARGET_METHOD.equals(name)
                        || !LoadingUtilsTextProcessor.TARGET_DESC.equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitVarInsn(int opcode, int varIndex) {
                        if (opcode == Opcodes.ALOAD) {
                            aloadVar[0] = varIndex;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (LoadingUtilsTextProcessor.HELPER_OWNER.equals(owner)
                                && LoadingUtilsTextProcessor.HELPER_METHOD.equals(methodName)
                                && LoadingUtilsTextProcessor.TARGET_DESC.equals(methodDesc)) {
                            helperCall[0] = true;
                        }
                    }
                };
            }
        }, 0);

        assertTrue(helperCall[0], "LoadingUtils text read should delegate to LoadingTextResourceReader.read");
        assertEquals(0, aloadVar[0], "Static LoadingUtils text read should load InputStream from local slot 0");
    }

    private byte[] createFakeLoadingUtilsClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, LoadingUtilsTextProcessor.TARGET_CLASS, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, LoadingUtilsTextProcessor.TARGET_METHOD,
                LoadingUtilsTextProcessor.TARGET_DESC, null, new String[]{"java/io/IOException"});
        mv.visitCode();
        mv.visitLdcInsn("old");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}