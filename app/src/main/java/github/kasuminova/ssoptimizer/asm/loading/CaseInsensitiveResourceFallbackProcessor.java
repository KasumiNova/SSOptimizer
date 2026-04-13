package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;

/**
 * 大小写不敏感资源加载回退处理器。
 * <p>
 * 注入目标：{@code com.fs.util.ResourceLoader}<br>
 * 注入动机：Starsector 模组通常在 Windows（大小写不敏感文件系统）上开发，资源路径中的
 * 大小写可能与实际文件不一致。在 Linux 上运行时，精确大小写匹配会导致资源加载失败并
 * 崩溃退出，原版引擎不提供任何回退或诊断信息。<br>
 * 为什么 Mixin 无法实现：需要在 {@code openStream} 方法级别添加 try-catch 包装，
 * 将原方法体重命名为内部实现后生成包装方法；这属于 Mixin 不支持的整体方法体包装场景。<br>
 * 注入效果：将 {@code openStream(String)} 重命名为内部实现方法
 * {@code ssoptimizer$openStreamCaseImpl}，生成新的 {@code openStream} 包含 try-catch
 * 包装——当原始加载抛出 {@link RuntimeException} 时，通过
 * {@link github.kasuminova.ssoptimizer.common.loading.CaseInsensitiveResourceFallback}
 * 尝试大小写不敏感路径匹配，成功则返回匹配到的资源流并记录告警，失败则重新抛出原始异常。
 */
public final class CaseInsensitiveResourceFallbackProcessor implements AsmClassProcessor {

    /**
     * 目标类：ResourceLoader。
     */
    public static final String TARGET_CLASS = GameClassNames.RESOURCE_LOADER;

    /**
     * 目标方法：openStream（经 tiny 映射后的 named 名称）。
     */
    public static final String TARGET_METHOD = GameMemberNames.ResourceLoader.OPEN_STREAM;

    /**
     * openStream 方法描述符。
     */
    public static final String TARGET_DESC = "(Ljava/lang/String;)Ljava/io/InputStream;";

    /**
     * 重命名后的内部实现方法名。原始方法体（包含其他处理器注入的逻辑）将保存到此方法中。
     */
    public static final String IMPL_METHOD = "ssoptimizer$openStreamCaseImpl";

    /**
     * 回退辅助类的内部名。
     */
    public static final String HELPER_OWNER =
            "github/kasuminova/ssoptimizer/common/loading/CaseInsensitiveResourceFallback";

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
                    // 将原始方法体写入到重命名后的内部方法，设为 private synthetic
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

    /**
     * 生成 openStream 的 try-catch 包装方法。
     * <p>
     * 生成的字节码等效于：
     * <pre>
     * InputStream openStream(String path) {
     *     try {
     *         return ssoptimizer$openStreamCaseImpl(path);
     *     } catch (RuntimeException e) {
     *         InputStream fallback = CaseInsensitiveResourceFallback.tryResolve(path, e);
     *         if (fallback != null) return fallback;
     *         throw e;
     *     }
     * }
     * </pre>
     *
     * @param cv             类访问器（ClassWriter 代理）
     * @param originalAccess 原始方法的访问修饰符
     * @param isStatic       原始方法是否为静态方法
     */
    private void generateWrapper(final ClassVisitor cv, final int originalAccess, final boolean isStatic) {
        final MethodVisitor mv = cv.visitMethod(originalAccess, TARGET_METHOD, TARGET_DESC, null, null);
        mv.visitCode();

        final Label tryStart = new Label();
        final Label tryEnd = new Label();
        final Label catchHandler = new Label();

        mv.visitTryCatchBlock(tryStart, tryEnd, catchHandler, "java/lang/RuntimeException");

        final int pathSlot = isStatic ? 0 : 1;
        final int exceptionSlot = isStatic ? 1 : 2;

        // -- try 块：调用重命名后的内部实现 --
        mv.visitLabel(tryStart);
        if (!isStatic) {
            mv.visitVarInsn(Opcodes.ALOAD, 0); // this
        }
        mv.visitVarInsn(Opcodes.ALOAD, pathSlot); // path

        if (isStatic) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, TARGET_CLASS, IMPL_METHOD, TARGET_DESC, false);
        } else {
            // 内部实现方法已设为 private，使用 INVOKESPECIAL 精确调用
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, TARGET_CLASS, IMPL_METHOD, TARGET_DESC, false);
        }

        mv.visitLabel(tryEnd);
        mv.visitInsn(Opcodes.ARETURN);

        // -- catch (RuntimeException e) --
        mv.visitLabel(catchHandler);
        mv.visitVarInsn(Opcodes.ASTORE, exceptionSlot); // 保存异常

        // 调用回退辅助方法：CaseInsensitiveResourceFallback.tryResolve(path, exception)
        mv.visitVarInsn(Opcodes.ALOAD, pathSlot);
        mv.visitVarInsn(Opcodes.ALOAD, exceptionSlot);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER_OWNER, "tryResolve",
                "(Ljava/lang/String;Ljava/lang/RuntimeException;)Ljava/io/InputStream;", false);

        // 检查返回值是否为 null
        mv.visitInsn(Opcodes.DUP);
        final Label rethrow = new Label();
        mv.visitJumpInsn(Opcodes.IFNULL, rethrow);
        mv.visitInsn(Opcodes.ARETURN); // 回退成功，返回流

        // 回退失败，重新抛出原始异常
        mv.visitLabel(rethrow);
        mv.visitInsn(Opcodes.POP); // 弹出 null
        mv.visitVarInsn(Opcodes.ALOAD, exceptionSlot);
        mv.visitInsn(Opcodes.ATHROW);

        mv.visitMaxs(0, 0); // COMPUTE_FRAMES 自动计算
        mv.visitEnd();
    }
}
