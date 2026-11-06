package github.kasuminova.ssoptimizer.asm.launcher;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import org.objectweb.asm.*;

/** 需要在构造器特定常量指令前提前返回并读取局部变量，Mixin 无法稳定表达该注入位置，故使用 ASM。 */
public final class LauncherDirectStartProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = GameClassNames.STARFARER_LAUNCHER;
    public static final String TARGET_METHOD = "<init>";
    public static final String TARGET_DESC   = "(Z)V";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/common/launcher/LauncherDirectStarter";
    public static final String HELPER_METHOD = "tryDirectStart";
    public static final String HELPER_DESC   = "(Ljava/lang/String;)Z";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return AsmCommonSuperClassResolver.resolve(type1, type2);
            }
        };

        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String desc,
                                             final String signature, final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return delegate;
                }
                modified[0] = true;
                return new ConstructorAdapter(delegate);
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static final class ConstructorAdapter extends MethodVisitor {
        private boolean injected;

        private ConstructorAdapter(final MethodVisitor delegate) {
            super(Opcodes.ASM9, delegate);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            if (!injected && "legacyLauncher".equals(value)) {
                final Label continueLabel = new Label();
                visitVarInsn(Opcodes.ALOAD, 5);
                visitMethodInsn(Opcodes.INVOKESTATIC,
                        HELPER_OWNER,
                        HELPER_METHOD,
                        HELPER_DESC,
                        false);
                visitJumpInsn(Opcodes.IFEQ, continueLabel);
                visitInsn(Opcodes.RETURN);
                visitLabel(continueLabel);
                injected = true;
            }
            super.visitLdcInsn(value);
        }
    }
}