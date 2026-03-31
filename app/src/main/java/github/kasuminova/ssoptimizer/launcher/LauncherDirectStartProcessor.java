package github.kasuminova.ssoptimizer.launcher;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import github.kasuminova.ssoptimizer.agent.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;

/**
 * Injects an optional direct-start path into the launcher constructor, before
 * any UI is created, so automated smoke tests can enter the real game-loading
 * phase without competing with the launcher OpenGL context.
 */
public final class LauncherDirectStartProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = "com/fs/starfarer/StarfarerLauncher";
    public static final String TARGET_METHOD = "<init>";
    public static final String TARGET_DESC   = "(Z)V";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/launcher/LauncherDirectStarter";
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