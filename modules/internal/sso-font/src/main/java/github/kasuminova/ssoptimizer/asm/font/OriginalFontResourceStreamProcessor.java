package github.kasuminova.ssoptimizer.asm.font;

import github.kasuminova.ssoptimizer.api.AsmClassProcessor;
import github.kasuminova.ssoptimizer.bootstrap.AsmCommonSuperClassResolver;
import github.kasuminova.ssoptimizer.mapping.GameClassNames;
import github.kasuminova.ssoptimizer.mapping.GameMemberNames;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * 在资源流入口替换原版字体流；启动早期资源加载类不能依赖 Mixin 的变换时序，因此使用 ASM。
 */
public final class OriginalFontResourceStreamProcessor implements AsmClassProcessor {
    public static final String TARGET_CLASS  = GameClassNames.RESOURCE_LOADER;
    public static final String TARGET_METHOD = GameMemberNames.ResourceLoader.OPEN_STREAM;
    public static final String TARGET_DESC   = "(Ljava/lang/String;)Ljava/io/InputStream;";
    public static final String HELPER_OWNER  = "github/kasuminova/ssoptimizer/common/font/OriginalGameFontOverrides";
    public static final String HELPER_METHOD = "openStream";

    @Override
    public byte[] process(final byte[] classfileBuffer) {
        final ClassReader reader = new ClassReader(classfileBuffer);
        if (!TARGET_CLASS.equals(reader.getClassName())) {
            return null;
        }

        final boolean[] modified = {false};
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1,
                                                 final String type2) {
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
                if (!TARGET_METHOD.equals(name) || !TARGET_DESC.equals(desc)) {
                    return delegate;
                }

                modified[0] = true;
                final int resourcePathIndex = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
                return new AdviceAdapter(Opcodes.ASM9, delegate, access, name, desc) {
                    @Override
                    protected void onMethodEnter() {
                        visitVarInsn(Opcodes.ALOAD, resourcePathIndex);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                HELPER_OWNER,
                                HELPER_METHOD,
                                TARGET_DESC,
                                false);
                        dup();
                        final Label fallThrough = new Label();
                        visitJumpInsn(Opcodes.IFNULL, fallThrough);
                        visitInsn(Opcodes.ARETURN);
                        visitLabel(fallThrough);
                        visitInsn(Opcodes.POP);
                    }
                };
            }
        }, 0);

        return modified[0] ? writer.toByteArray() : null;
    }
}