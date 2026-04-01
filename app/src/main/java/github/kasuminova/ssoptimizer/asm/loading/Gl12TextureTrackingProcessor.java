package github.kasuminova.ssoptimizer.asm.loading;

import github.kasuminova.ssoptimizer.bootstrap.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * OpenGL 1.2 纹理资源追踪的 ASM 处理器。
 *
 * <p>注入目标：{@code org/lwjgl/opengl/GL12} 的 {@code glTexImage3D}<br>
 * 注入动机：需要追踪 3D 纹理分配（如体积纹理 / 数组纹理）以统计显存占用；
 * 与 {@link Gl11TextureTrackingProcessor} 同理，Mixin 无法 Hook LWJGL 的静态 native 方法。<br>
 * 注入效果：在 3D 纹理创建后插入 {@code RuntimeGlResourceTracker} 的追踪回调。</p>
 */
public final class Gl12TextureTrackingProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS      = "org/lwjgl/opengl/GL12";
    static final        String TEX_IMAGE_3D_DESC = "(IIIIIIIIILjava/nio/ByteBuffer;)V";

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
                if (!"glTexImage3D".equals(name) || !TEX_IMAGE_3D_DESC.equals(descriptor)) {
                    return delegate;
                }
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
                        loadArg(9);
                        visitMethodInsn(INVOKESTATIC, TRACKER_OWNER,
                                "afterTexImage3D", TEX_IMAGE_3D_DESC, false);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}
