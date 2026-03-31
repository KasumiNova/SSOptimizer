package github.kasuminova.ssoptimizer.loading;

import github.kasuminova.ssoptimizer.agent.AsmClassProcessor;
import github.kasuminova.ssoptimizer.agent.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;

/**
 * Replaces LoadingUtils' hot InputStream-to-String routine with a bulk UTF-8 reader.
 */
public final class LoadingUtilsTextProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = "com/fs/starfarer/loading/LoadingUtils";
    public static final String TARGET_METHOD = "super";
    public static final String TARGET_DESC   = "(Ljava/io/InputStream;)Ljava/lang/String;";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/loading/LoadingTextResourceReader";
    public static final String HELPER_METHOD = "read";

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
            public MethodVisitor visitMethod(final int access,
                                             final String name,
                                             final String desc,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(desc)) {
                    modified[0] = true;
                    return new MethodReplacer(delegate, (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1);
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }

    private static final class MethodReplacer extends MethodVisitor {
        private final MethodVisitor target;
        private final int           inputStreamLocalIndex;

        private MethodReplacer(final MethodVisitor target, final int inputStreamLocalIndex) {
            super(Opcodes.ASM9);
            this.target = target;
            this.inputStreamLocalIndex = inputStreamLocalIndex;
        }

        @Override
        public void visitCode() {
            target.visitCode();
            target.visitVarInsn(Opcodes.ALOAD, inputStreamLocalIndex);
            target.visitMethodInsn(Opcodes.INVOKESTATIC,
                    HELPER_OWNER,
                    HELPER_METHOD,
                    TARGET_DESC,
                    false);
            target.visitInsn(Opcodes.ARETURN);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            target.visitMaxs(0, 0);
        }

        @Override
        public void visitEnd() {
            target.visitEnd();
        }

        @Override
        public AnnotationVisitor visitAnnotation(final String descriptor, final boolean visible) {
            return target.visitAnnotation(descriptor, visible);
        }
    }
}