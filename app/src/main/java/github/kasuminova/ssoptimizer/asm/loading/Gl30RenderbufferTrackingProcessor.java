package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

public final class Gl30RenderbufferTrackingProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS              = "org/lwjgl/opengl/GL30";
    static final        String RENDERBUFFER_STORAGE_DESC = "(IIII)V";
    static final        String DELETE_RENDERBUFFER_DESC  = "(I)V";
    static final        String DELETE_RENDERBUFFERS_DESC = "(Ljava/nio/IntBuffer;)V";

    private static final String TRACKER_OWNER = "github/kasuminova/ssoptimizer/common/loading/RuntimeGlResourceTracker";

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
                                             final String descriptor,
                                             final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if ("glRenderbufferStorage".equals(name) && RENDERBUFFER_STORAGE_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(final int opcode) {
                            if (opcode == ATHROW) {
                                return;
                            }
                            loadArg(0);
                            loadArg(1);
                            loadArg(2);
                            loadArg(3);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "afterRenderbufferStorage", RENDERBUFFER_STORAGE_DESC, false);
                        }
                    };
                }
                if ("glDeleteRenderbuffers".equals(name) && DELETE_RENDERBUFFER_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            loadArg(0);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "beforeDeleteRenderbuffer", DELETE_RENDERBUFFER_DESC, false);
                        }
                    };
                }
                if ("glDeleteRenderbuffers".equals(name) && DELETE_RENDERBUFFERS_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            loadArg(0);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "beforeDeleteRenderbuffers", DELETE_RENDERBUFFERS_DESC, false);
                        }
                    };
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
