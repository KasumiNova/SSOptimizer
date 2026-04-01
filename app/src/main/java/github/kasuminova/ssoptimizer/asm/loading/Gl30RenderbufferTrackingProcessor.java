package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * OpenGL 3.0 渲染缓冲区资源追踪的 ASM 处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/GL30} 的 {@code glRenderbufferStorage / glDeleteRenderbuffers}<br>
 * 注入动机：需要追踪 Renderbuffer 的分配和释放以统计显存占用；
 * 与纹理追踪处理器同理，Mixin 无法 Hook LWJGL 的静态 native 方法。<br>
 * 注入效果：在 Renderbuffer 分配、删除操作前后插入 {@code RuntimeGlResourceTracker} 的追踪回调。</p>
 */
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
