package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.*;

/**
 * 资源加载器文件访问优化处理器。
 * <p>
 * 注入目标：{@code com.fs.util.ResourceLoader}<br>
 * 注入动机：原版启动时会对同一批路径反复执行 {@code exists}/{@code isDirectory}/
 * {@code lastModified}/{@code listFiles} 等文件系统探测，造成大量冗余 I/O；
 * 通过 ASM 把这些调用转发到缓存层，可以在不修改上层逻辑的前提下降低磁盘访问压力。<br>
 * 注入效果：把 {@link java.io.File} 的多种查询调用替换为
 * {@link github.kasuminova.ssoptimizer.common.loading.ResourceFileCache} 的缓存实现。
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