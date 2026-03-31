package github.kasuminova.ssoptimizer.asm.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;

import static org.junit.jupiter.api.Assertions.*;

class ResourceLoaderFileAccessProcessorTest {
    @Test
    void rewritesFileMetadataCallsToCacheHelper() {
        byte[] rewritten = new ResourceLoaderFileAccessProcessor().process(createFakeResourceLoaderClass());
        assertNotNull(rewritten);

        Inspection inspection = inspectMethod(rewritten);
        assertTrue(inspection.callsExistsHelper, "exists should be routed through ResourceFileCache");
        assertTrue(inspection.callsDirectoryHelper, "isDirectory should be routed through ResourceFileCache");
        assertTrue(inspection.callsLastModifiedHelper, "lastModified should be routed through ResourceFileCache");
        assertTrue(inspection.callsListFilesHelper, "listFiles should be routed through ResourceFileCache");
        assertFalse(inspection.callsVirtualFileMetadata, "Direct File metadata calls should be removed after rewrite");
    }

    private byte[] createFakeResourceLoaderClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC,
                ResourceLoaderFileAccessProcessor.TARGET_CLASS,
                null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "probe", "(Ljava/io/File;Ljava/io/FilenameFilter;)J", null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "exists", "()Z", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "isDirectory", "()Z", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "lastModified", "()J", false);
        mv.visitInsn(Opcodes.POP2);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "listFiles", "()[Ljava/io/File;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "listFiles", "(Ljava/io/FilenameFilter;)[Ljava/io/File;", false);
        mv.visitInsn(Opcodes.POP);

        mv.visitInsn(Opcodes.LCONST_0);
        mv.visitInsn(Opcodes.LRETURN);
        mv.visitMaxs(0, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private Inspection inspectMethod(byte[] classBytes) {
        boolean[] existsHelper = {false};
        boolean[] directoryHelper = {false};
        boolean[] lastModifiedHelper = {false};
        boolean[] listFilesHelper = {false};
        boolean[] virtualFileMetadata = {false};

        new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                if (!"probe".equals(name) || !"(Ljava/io/File;Ljava/io/FilenameFilter;)J".equals(desc)) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName,
                                                String methodDesc, boolean itf) {
                        if (ResourceLoaderFileAccessProcessor.HELPER_OWNER.equals(owner)) {
                            if ("exists".equals(methodName) && "(Ljava/io/File;)Z".equals(methodDesc)) {
                                existsHelper[0] = true;
                            }
                            if ("isDirectory".equals(methodName) && "(Ljava/io/File;)Z".equals(methodDesc)) {
                                directoryHelper[0] = true;
                            }
                            if ("lastModified".equals(methodName) && "(Ljava/io/File;)J".equals(methodDesc)) {
                                lastModifiedHelper[0] = true;
                            }
                            if ("listFiles".equals(methodName)
                                    && ("(Ljava/io/File;)[Ljava/io/File;".equals(methodDesc)
                                    || "(Ljava/io/File;Ljava/io/FilenameFilter;)[Ljava/io/File;".equals(methodDesc))) {
                                listFilesHelper[0] = true;
                            }
                        }
                        if ("java/io/File".equals(owner)
                                && ("exists".equals(methodName)
                                || "isDirectory".equals(methodName)
                                || "lastModified".equals(methodName)
                                || "listFiles".equals(methodName))) {
                            virtualFileMetadata[0] = true;
                        }
                    }
                };
            }
        }, 0);

        return new Inspection(existsHelper[0], directoryHelper[0], lastModifiedHelper[0], listFilesHelper[0], virtualFileMetadata[0]);
    }

    private record Inspection(boolean callsExistsHelper,
                              boolean callsDirectoryHelper,
                              boolean callsLastModifiedHelper,
                              boolean callsListFilesHelper,
                              boolean callsVirtualFileMetadata) {
    }
}