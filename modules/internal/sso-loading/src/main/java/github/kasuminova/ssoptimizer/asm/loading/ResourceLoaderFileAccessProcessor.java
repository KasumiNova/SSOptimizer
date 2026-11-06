package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.*;

/**
 * 遍历 ResourceLoader 的全部方法并重定向 File 查询指令；这种未枚举方法范围内的指令扫描无法由 Mixin 注入点表达，因此使用 ASM。
 */
public final class ResourceLoaderFileAccessProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS = GameClassNames.RESOURCE_LOADER;
    public static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/common/loading/ResourceFileCache";

    @Override
    public byte[] process(byte[] classfileBuffer) {
        ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, sig, ex);
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String methodName, String methodDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && "java/io/File".equals(owner)) {
                            if ("exists".equals(methodName) && "()Z".equals(methodDesc)) {
                                modified[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                        "exists", "(Ljava/io/File;)Z", false);
                                return;
                            }
                            if ("isDirectory".equals(methodName) && "()Z".equals(methodDesc)) {
                                modified[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                        "isDirectory", "(Ljava/io/File;)Z", false);
                                return;
                            }
                            if ("lastModified".equals(methodName) && "()J".equals(methodDesc)) {
                                modified[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                        "lastModified", "(Ljava/io/File;)J", false);
                                return;
                            }
                            if ("listFiles".equals(methodName) && "()[Ljava/io/File;".equals(methodDesc)) {
                                modified[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                        "listFiles", "(Ljava/io/File;)[Ljava/io/File;", false);
                                return;
                            }
                            if ("listFiles".equals(methodName) && "(Ljava/io/FilenameFilter;)[Ljava/io/File;".equals(methodDesc)) {
                                modified[0] = true;
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER,
                                        "listFiles", "(Ljava/io/File;Ljava/io/FilenameFilter;)[Ljava/io/File;", false);
                                return;
                            }
                        }

                        super.visitMethodInsn(opcode, owner, methodName, methodDesc, itf);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}