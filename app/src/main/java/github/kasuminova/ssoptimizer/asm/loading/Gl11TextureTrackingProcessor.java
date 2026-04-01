package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * OpenGL 1.1 纹理资源追踪的 ASM 处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/GL11} 的 {@code glTexImage2D / glDeleteTextures}<br>
 * 注入动机：需要追踪所有 2D 纹理的分配和释放以统计显存占用；LWJGL 的 GL 类为 JNI
 * 桥接类，Mixin 无法 Hook 静态 native 方法的调用点。<br>
 * 注入效果：在纹理操作前后插入 {@code RuntimeGlResourceTracker} 的追踪回调。</p>
 */
public final class Gl11TextureTrackingProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS         = "org/lwjgl/opengl/GL11";
    static final        String TEX_IMAGE_2D_DESC    = "(IIIIIIIILjava/nio/ByteBuffer;)V";
    static final        String DELETE_TEXTURE_DESC  = "(I)V";
    static final        String DELETE_TEXTURES_DESC = "(Ljava/nio/IntBuffer;)V";

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
                if ("glTexImage2D".equals(name) && TEX_IMAGE_2D_DESC.equals(descriptor)) {
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
                            loadArg(4);
                            loadArg(5);
                            loadArg(6);
                            loadArg(7);
                            loadArg(8);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "afterTexImage2D", TEX_IMAGE_2D_DESC, false);
                        }
                    };
                }
                if ("glDeleteTextures".equals(name) && DELETE_TEXTURE_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            loadArg(0);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "beforeDeleteTexture", DELETE_TEXTURE_DESC, false);
                        }
                    };
                }
                if ("glDeleteTextures".equals(name) && DELETE_TEXTURES_DESC.equals(descriptor)) {
                    modified[0] = true;
                    return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            loadArg(0);
                            visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                    "beforeDeleteTextures", DELETE_TEXTURES_DESC, false);
                        }
                    };
                }
                return delegate;
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
