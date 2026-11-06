package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * 将 openStream 原方法体重命名并生成带 RuntimeException 回退的包装方法。
 * Mixin 无法完成整体方法体包装，因此使用 ASM；进入 try 前必须捕获线程隔离状态，避免 openResource 消费后回退通道跨越模组资源边界。
 */
public final class CaseInsensitiveResourceFallbackProcessor implements AsmClassProcessor {

    public static final String TARGET_CLASS = GameClassNames.RESOURCE_LOADER;

    public static final String TARGET_METHOD = GameMemberNames.ResourceLoader.OPEN_STREAM;

    public static final String TARGET_DESC = "(Ljava/lang/String;)Ljava/io/InputStream;";

    public static final String IMPL_METHOD = "ssoptimizer$openStreamCaseImpl";

    public static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/common/loading/CaseInsensitiveResourceFallback";

    private static final String THREAD_STATE_OWNER =
            "github/kasuminova/ssoptimizer/common/loading/ResourceLoaderThreadState";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final boolean[] isStatic = {false};
        final int[] originalAccess = {0};

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return "java/lang/Object";
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String signature, final String[] exceptions) {
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    isStatic[0] = (access & Opcodes.ACC_STATIC) != 0;
                    originalAccess[0] = access;
                    final int implAccess = (access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED))
                            | Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC;
                    return super.visitMethod(implAccess, IMPL_METHOD, desc, signature, exceptions);
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (modified[0]) {
                    generateWrapper(cv, originalAccess[0], isStatic[0]);
                }
                super.visitEnd();
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private void generateWrapper(final ClassVisitor cv, final int originalAccess, final boolean isStatic) {
        final MethodVisitor mv = cv.visitMethod(originalAccess, TARGET_METHOD, TARGET_DESC, null, null);
        mv.visitCode();

        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label catchHandler = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/RuntimeException");

        final int pathSlot = isStatic ? 0 : 1;
        final int exceptionSlot = isStatic ? 1 : 2;
        final int filterSlot = isStatic ? 2 : 3;
        final int suppressSlot = isStatic ? 3 : 4;

        mv.visitMethodInsn(Opcodes.INVOKESTATIC, THREAD_STATE_OWNER, "getSourceFilter",
                "()Ljava/lang/String;", false);
        mv.visitVarInsn(Opcodes.ASTORE, filterSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, THREAD_STATE_OWNER, "isSuppressCustomResources",
                "()Z", false);
        mv.visitVarInsn(Opcodes.ISTORE, suppressSlot);

        mv.visitLabel(tryStart);
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        }
        mv.visitVarInsn(Opcodes.ALOAD, pathSlot);

        if (isStatic) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, IMPL_METHOD, TARGET_DESC, false);
        } else {
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET_CLASS, IMPL_METHOD, TARGET_DESC, false);
        }

        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(catchHandler);
        mv.visitVarInsn(Opcodes.ASTORE, exceptionSlot);

        mv.visitVarInsn(Opcodes.ALOAD, pathSlot);
        mv.visitVarInsn(Opcodes.ALOAD, exceptionSlot);
        mv.visitVarInsn(Opcodes.ALOAD, filterSlot);
        mv.visitVarInsn(Opcodes.ILOAD, suppressSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "tryResolve",
                "(Ljava/lang/String;Ljava/lang/RuntimeException;Ljava/lang/String;Z)Ljava/io/InputStream;", false);

        mv.visitInsn(Opcodes.DUP);
        final Label rethrow = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, rethrow);
        mv.visitInsn(Opcodes.ARETURN);

        mv.visitLabel(rethrow);
        mv.visitInsn(Opcodes.POP);
        mv.visitVarInsn(Opcodes.ALOAD, exceptionSlot);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }
}
